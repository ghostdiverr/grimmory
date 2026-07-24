package org.booklore.service.acquisition;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.WantedBookMapper;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.acquisition.CreateWantedBookRequest;
import org.booklore.model.dto.acquisition.GrabRequest;
import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.dto.acquisition.WantedBookDto;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.entity.WantedBookEntity.Status;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@AllArgsConstructor
public class WantedBookService {

    private static final double MATCH_THRESHOLD = 0.6;

    private final WantedBookRepository wantedBookRepository;
    private final WantedBookMapper wantedBookMapper;
    private final AcquisitionJobRepository acquisitionJobRepository;
    private final AcquisitionService acquisitionService;
    private final NotificationService notificationService;
    private final AuthenticationService authenticationService;
    private final UserRepository userRepository;

    @Transactional
    public WantedBookDto create(CreateWantedBookRequest request) {
        WantedBookEntity entry = WantedBookEntity.builder()
                .title(request.title())
                .author(request.author())
                .isbn(request.isbn())
                .category(request.category())
                .libraryId(request.libraryId())
                .status(Status.ACTIVE)
                .requestedByUserId(authenticationService.getAuthenticatedUser().getId())
                .build();

        entry = wantedBookRepository.save(entry);
        log.info("Added wanted list entry {} for '{}'", entry.getId(), entry.getTitle());
        return publishAndReturn(entry);
    }

    @Transactional(readOnly = true)
    public List<WantedBookDto> list() {
        return wantedBookRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(wantedBookMapper::toDto)
                .toList();
    }

    @Transactional
    public WantedBookDto pause(Long id) {
        WantedBookEntity entry = getOrThrow(id);
        entry.setStatus(Status.PAUSED);
        entry = wantedBookRepository.save(entry);
        return publishAndReturn(entry);
    }

    @Transactional
    public WantedBookDto resume(Long id) {
        WantedBookEntity entry = getOrThrow(id);
        entry.setStatus(Status.ACTIVE);
        entry = wantedBookRepository.save(entry);
        return publishAndReturn(entry);
    }

    @Transactional
    public void delete(Long id) {
        WantedBookEntity entry = getOrThrow(id);
        wantedBookRepository.delete(entry);
    }

    /**
     * Periodic scan invoked by {@code WantedListScanTask}: reconciles in-flight entries
     * against their acquisition job's outcome, then searches Prowlarr for every entry
     * still active and immediately grabs the best matching release, if any.
     */
    @Transactional
    public void runScanCycle() {
        reconcileGrabbedEntries();
        searchAndGrabActiveEntries();
    }

    private void reconcileGrabbedEntries() {
        for (WantedBookEntity entry : wantedBookRepository.findAllByStatus(Status.GRABBED)) {
            if (entry.getAcquisitionJobId() == null) {
                continue;
            }
            acquisitionJobRepository.findById(entry.getAcquisitionJobId()).ifPresent(job -> {
                if (job.getStatus() == AcquisitionJobEntity.Status.COMPLETED) {
                    entry.setStatus(Status.FULFILLED);
                    wantedBookRepository.save(entry);
                    log.info("Wanted list entry {} fulfilled by acquisition job {}", entry.getId(), job.getId());
                    publishAndReturn(entry);
                } else if (job.getStatus() == AcquisitionJobEntity.Status.FAILED
                        || job.getStatus() == AcquisitionJobEntity.Status.TIMED_OUT) {
                    entry.setStatus(Status.ACTIVE);
                    entry.setAcquisitionJobId(null);
                    entry.setExcludedGuids(addExcludedGuid(entry.getExcludedGuids(), job.getReleaseGuid()));
                    wantedBookRepository.save(entry);
                    log.info("Wanted list entry {} reset to ACTIVE after job {} {}", entry.getId(), job.getId(), job.getStatus());
                    publishAndReturn(entry);
                }
            });
        }
    }

    private void searchAndGrabActiveEntries() {
        for (WantedBookEntity entry : wantedBookRepository.findAllByStatus(Status.ACTIVE)) {
            String query = entry.getAuthor() != null && !entry.getAuthor().isBlank()
                    ? entry.getTitle() + " " + entry.getAuthor()
                    : entry.getTitle();

            List<ProwlarrReleaseDto> results = acquisitionService.search(query, entry.getCategory());
            Set<String> excludedGuids = parseExcludedGuids(entry.getExcludedGuids());

            ProwlarrReleaseDto bestMatch = results.stream()
                    .filter(release -> !excludedGuids.contains(release.guid()))
                    .filter(release -> AcquisitionFolderWatcherService.matchScore(query, release.title()) >= MATCH_THRESHOLD)
                    .max(Comparator.comparing(release -> release.seeders() == null ? 0 : release.seeders()))
                    .orElse(null);

            entry.setLastSearchedAt(Instant.now());
            entry.setLastResultCount(results.size());

            if (bestMatch != null) {
                AcquisitionJobDto job = acquisitionService.grab(
                        new GrabRequest(bestMatch, query, null, entry.getLibraryId()),
                        entry.getRequestedByUserId());
                entry.setStatus(Status.GRABBED);
                entry.setAcquisitionJobId(job.getId());
                log.info("Wanted list entry {} auto-grabbed release '{}' (job {})", entry.getId(), bestMatch.title(), job.getId());
            }

            wantedBookRepository.save(entry);
            publishAndReturn(entry);
        }
    }

    private static Set<String> parseExcludedGuids(String excludedGuids) {
        if (excludedGuids == null || excludedGuids.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(excludedGuids.split(",")));
    }

    private static String addExcludedGuid(String excludedGuids, String guid) {
        if (guid == null) {
            return excludedGuids;
        }
        Set<String> guids = new LinkedHashSet<>(parseExcludedGuids(excludedGuids));
        guids.add(guid);
        return String.join(",", guids);
    }

    private WantedBookEntity getOrThrow(Long id) {
        return wantedBookRepository.findById(id)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Wanted list entry not found with ID: " + id));
    }

    private WantedBookDto publishAndReturn(WantedBookEntity entry) {
        WantedBookDto dto = wantedBookMapper.toDto(entry);
        userRepository.findById(entry.getRequestedByUserId())
                .map(BookLoreUserEntity::getUsername)
                .ifPresentOrElse(
                        username -> notificationService.sendMessageToUser(username, Topic.WANTED_BOOK_UPDATE, dto),
                        () -> log.warn("Could not resolve username for user id {}, skipping websocket push for wanted entry {}",
                                entry.getRequestedByUserId(), entry.getId())
                );
        return dto;
    }
}
