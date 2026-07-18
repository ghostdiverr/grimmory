package org.booklore.task.tasks;

import org.booklore.exception.APIException;
import org.booklore.mapper.AcquisitionJobMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.model.enums.TaskType;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.acquisition.AcquisitionFolderWatcherService;
import org.booklore.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcquisitionQueueScanTaskTest {

    @Mock
    private AcquisitionFolderWatcherService acquisitionFolderWatcherService;
    @Mock
    private AcquisitionJobRepository acquisitionJobRepository;
    @Mock
    private AcquisitionJobMapper acquisitionJobMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AcquisitionQueueScanTask task;

    private BookLoreUser user;
    private TaskCreateRequest request;

    @BeforeEach
    void setUp() {
        user = BookLoreUser.builder()
                .permissions(new BookLoreUser.UserPermissions())
                .build();
        request = new TaskCreateRequest();

        lenient().when(acquisitionJobMapper.toDto(any(AcquisitionJobEntity.class))).thenReturn(new AcquisitionJobDto());
        lenient().when(acquisitionJobRepository.save(any(AcquisitionJobEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void validatePermissions_shouldThrowException_whenUserCannotAccessTaskManager() {
        user.getPermissions().setCanAccessTaskManager(false);
        assertThrows(APIException.class, () -> task.validatePermissions(user, request));
    }

    @Test
    void validatePermissions_shouldPass_whenUserCanAccessTaskManager() {
        user.getPermissions().setCanAccessTaskManager(true);
        assertDoesNotThrow(() -> task.validatePermissions(user, request));
    }

    @Test
    void execute_triggersRescanOfCompletedFolder() {
        when(acquisitionJobRepository.findAllByStatusIn(anyList())).thenReturn(List.of());

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskType.ACQUISITION_QUEUE_SCAN, response.getTaskType());
        assertEquals(TaskStatus.COMPLETED, response.getStatus());
        verify(acquisitionFolderWatcherService).rescanCompletedFolder();
    }

    @Test
    void execute_flipsJobsOlderThanSixHoursToTimedOut() {
        AcquisitionJobEntity staleJob = AcquisitionJobEntity.builder()
                .id(1L)
                .category(AcquisitionCategory.BOOK)
                .releaseTitle("Stale Release")
                .status(AcquisitionJobEntity.Status.IMPORTING)
                .requestedByUserId(1L)
                .grabbedAt(Instant.now().minus(7, ChronoUnit.HOURS))
                .build();

        when(acquisitionJobRepository.findAllByStatusIn(anyList())).thenReturn(List.of(staleJob));

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskStatus.COMPLETED, response.getStatus());
        assertEquals(AcquisitionJobEntity.Status.TIMED_OUT, staleJob.getStatus());
        assertNotNull(staleJob.getCompletedAt());
    }

    @Test
    void execute_leavesJobsWithinTimeoutThresholdUntouched() {
        AcquisitionJobEntity freshJob = AcquisitionJobEntity.builder()
                .id(2L)
                .category(AcquisitionCategory.BOOK)
                .releaseTitle("Fresh Release")
                .status(AcquisitionJobEntity.Status.WAITING_FOR_FILE)
                .requestedByUserId(1L)
                .grabbedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();

        when(acquisitionJobRepository.findAllByStatusIn(anyList())).thenReturn(List.of(freshJob));

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskStatus.COMPLETED, response.getStatus());
        assertEquals(AcquisitionJobEntity.Status.WAITING_FOR_FILE, freshJob.getStatus());
        assertNull(freshJob.getCompletedAt());
        verify(acquisitionJobRepository, never()).save(freshJob);
    }

    @Test
    void execute_returnsFailed_whenRescanThrows() {
        doThrow(new RuntimeException("rescan failed")).when(acquisitionFolderWatcherService).rescanCompletedFolder();

        TaskCreateResponse response = task.execute(request);

        assertEquals(TaskType.ACQUISITION_QUEUE_SCAN, response.getTaskType());
        assertEquals(TaskStatus.FAILED, response.getStatus());
    }
}
