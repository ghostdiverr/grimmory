package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.AcquisitionJobMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.acquisition.AcquisitionFolderWatcherService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AcquisitionQueueScanTask implements Task {

    private static final Duration STALE_JOB_TIMEOUT = Duration.ofHours(6);

    private static final List<AcquisitionJobEntity.Status> IN_FLIGHT_STATUSES = List.of(
            AcquisitionJobEntity.Status.WAITING_FOR_FILE,
            AcquisitionJobEntity.Status.NORMALIZING,
            AcquisitionJobEntity.Status.IMPORTING
    );

    private final AcquisitionFolderWatcherService acquisitionFolderWatcherService;
    private final AcquisitionJobRepository acquisitionJobRepository;
    private final AcquisitionJobMapper acquisitionJobMapper;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(getTaskType());

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        try {
            acquisitionFolderWatcherService.rescanCompletedFolder();
            timeOutStaleJobs();
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error scanning acquisition queue", getTaskType(), e);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.ACQUISITION_QUEUE_SCAN;
    }

    private void timeOutStaleJobs() {
        Instant cutoff = Instant.now().minus(STALE_JOB_TIMEOUT);
        List<AcquisitionJobEntity> inFlightJobs = acquisitionJobRepository.findAllByStatusIn(IN_FLIGHT_STATUSES);

        for (AcquisitionJobEntity job : inFlightJobs) {
            if (job.getGrabbedAt() == null || job.getGrabbedAt().isAfter(cutoff)) {
                continue;
            }
            job.setStatus(AcquisitionJobEntity.Status.TIMED_OUT);
            job.setCompletedAt(Instant.now());
            AcquisitionJobEntity saved = acquisitionJobRepository.save(job);
            log.info("Acquisition job {} timed out (stuck since {})", saved.getId(), saved.getGrabbedAt());
            publishUpdate(saved);
        }
    }

    private void publishUpdate(AcquisitionJobEntity job) {
        AcquisitionJobDto dto = acquisitionJobMapper.toDto(job);
        userRepository.findById(job.getRequestedByUserId())
                .map(BookLoreUserEntity::getUsername)
                .ifPresentOrElse(
                        username -> notificationService.sendMessageToUser(username, Topic.ACQUISITION_JOB_UPDATE, dto),
                        () -> log.warn("Could not resolve username for user id {}, skipping websocket push for acquisition job {}",
                                job.getRequestedByUserId(), job.getId())
                );
    }
}
