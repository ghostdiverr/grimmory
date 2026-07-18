package org.booklore.service.acquisition;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.AcquisitionJobMapper;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.acquisition.GrabRequest;
import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class AcquisitionService {

    private final ProwlarrClient prowlarrClient;
    private final AcquisitionJobRepository acquisitionJobRepository;
    private final AcquisitionJobMapper acquisitionJobMapper;
    private final NotificationService notificationService;
    private final AuthenticationService authenticationService;

    /**
     * Pure passthrough to Prowlarr — no job/persistence side effects.
     */
    public List<ProwlarrReleaseDto> search(String query, AcquisitionCategory category) {
        return prowlarrClient.search(query, category);
    }

    @Transactional
    public AcquisitionJobDto grab(GrabRequest request) {
        ProwlarrReleaseDto release = request.release();
        prowlarrClient.grab(release);

        Long requestedByUserId = authenticationService.getAuthenticatedUser().getId();

        AcquisitionJobEntity job = AcquisitionJobEntity.builder()
                .bookId(request.bookId())
                .libraryId(request.libraryId())
                .query(request.query())
                .category(release.category())
                .releaseTitle(release.title())
                .releaseGuid(release.guid())
                .indexerId(release.indexerId())
                .indexerName(release.indexerName())
                .protocol(release.protocol())
                .sizeBytes(release.size())
                .status(AcquisitionJobEntity.Status.GRABBED)
                .requestedByUserId(requestedByUserId)
                .grabbedAt(Instant.now())
                .build();

        job = acquisitionJobRepository.save(job);
        log.info("Grabbed release '{}' (guid={}) as acquisition job {}", release.title(), release.guid(), job.getId());
        // Publish the GRABBED moment on its own message so it's distinguishable from "now
        // watching for the file" below (matters if a later phase adds live queue-status
        // polling for the in-between state).
        publishAndReturn(job);

        job.setStatus(AcquisitionJobEntity.Status.WAITING_FOR_FILE);
        job = acquisitionJobRepository.save(job);
        log.info("Acquisition job {} is now waiting for the downloaded file", job.getId());

        return publishAndReturn(job);
    }

    @Transactional(readOnly = true)
    public List<AcquisitionJobDto> listJobs() {
        return acquisitionJobRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(acquisitionJobMapper::toDto)
                .toList();
    }

    /**
     * Best-effort cancellation. Prowlarr has no cancel-grab API — it only forwards the
     * grab to whichever download client is configured on its side, so the underlying
     * download keeps running there. This only flips the local job's status so it stops
     * being tracked/waited-on by the completed-download watcher (WP4).
     */
    @Transactional
    public AcquisitionJobDto cancelJob(Long id) {
        AcquisitionJobEntity job = acquisitionJobRepository.findById(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Acquisition job not found with ID: " + id));

        job.setStatus(AcquisitionJobEntity.Status.FAILED);
        job.setErrorMessage("Cancelled by user");
        job.setCompletedAt(Instant.now());
        job = acquisitionJobRepository.save(job);

        return publishAndReturn(job);
    }

    private AcquisitionJobDto publishAndReturn(AcquisitionJobEntity job) {
        AcquisitionJobDto dto = acquisitionJobMapper.toDto(job);
        notificationService.sendMessage(Topic.ACQUISITION_JOB_UPDATE, dto);
        return dto;
    }
}
