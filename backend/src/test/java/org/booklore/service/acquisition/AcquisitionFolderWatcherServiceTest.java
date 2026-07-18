package org.booklore.service.acquisition;

import org.booklore.config.AppProperties;
import org.booklore.mapper.AcquisitionJobMapper;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.ProwlarrSettings;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.acquisition.normalize.NormalizationResult;
import org.booklore.service.acquisition.normalize.ReleaseNormalizationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.bookdrop.BookdropEventHandlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AcquisitionFolderWatcherServiceTest {

    @TempDir
    Path completedDownloadsDir;

    @TempDir
    Path bookdropDir;

    private AppProperties appProperties;
    private AppSettingService appSettingService;
    private AcquisitionJobRepository acquisitionJobRepository;
    private AcquisitionJobMapper acquisitionJobMapper;
    private NotificationService notificationService;
    private UserRepository userRepository;
    private ReleaseNormalizationService releaseNormalizationService;
    private BookdropEventHandlerService bookdropEventHandlerService;

    private AcquisitionFolderWatcherService watcher;

    @BeforeEach
    void setUp() {
        appProperties = mock(AppProperties.class);
        appSettingService = mock(AppSettingService.class);
        acquisitionJobRepository = mock(AcquisitionJobRepository.class);
        acquisitionJobMapper = mock(AcquisitionJobMapper.class);
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        releaseNormalizationService = mock(ReleaseNormalizationService.class);
        bookdropEventHandlerService = mock(BookdropEventHandlerService.class);

        when(appProperties.getBookdropFolder()).thenReturn(bookdropDir.toString());

        ProwlarrSettings prowlarrSettings = ProwlarrSettings.builder()
                .enabled(true)
                .completedDownloadsFolder(completedDownloadsDir.toString())
                .build();
        AppSettings appSettings = AppSettings.builder().prowlarrSettings(prowlarrSettings).build();
        when(appSettingService.getAppSettings()).thenReturn(appSettings);

        when(acquisitionJobMapper.toDto(any(AcquisitionJobEntity.class))).thenReturn(new AcquisitionJobDto());
        when(acquisitionJobRepository.save(any(AcquisitionJobEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        watcher = new AcquisitionFolderWatcherService(
                appProperties, appSettingService, acquisitionJobRepository, acquisitionJobMapper,
                notificationService, userRepository, releaseNormalizationService, bookdropEventHandlerService);
    }

    private AcquisitionJobEntity waitingJob(long id, String releaseTitle) {
        return AcquisitionJobEntity.builder()
                .id(id)
                .category(AcquisitionCategory.BOOK)
                .releaseTitle(releaseTitle)
                .releaseGuid("guid-" + id)
                .indexerId(1L)
                .status(AcquisitionJobEntity.Status.WAITING_FOR_FILE)
                .requestedByUserId(1L)
                .grabbedAt(Instant.now())
                .query(releaseTitle)
                .build();
    }

    @Test
    void stabilityWait_isHonoredBeforeFileIsProcessed() throws IOException, InterruptedException {
        Path file = completedDownloadsDir.resolve("some-book-title.epub");
        Files.writeString(file, "initial-content");

        AcquisitionJobEntity job = waitingJob(1L, "Some Book Title");
        when(acquisitionJobRepository.findAllByStatusIn(anyList())).thenReturn(List.of(job));
        when(releaseNormalizationService.normalize(any(Path.class), any(AcquisitionCategory.class)))
                .thenReturn(new NormalizationResult.Ready(List.of(file)));

        // Simulate the download client still writing to the file shortly after it's first seen.
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(600);
                Files.write(file, "-more-content-appended-while-downloading".getBytes(),
                        StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // best-effort background writer
            }
        });
        writer.start();

        long start = System.currentTimeMillis();
        watcher.start();
        long elapsedMs = System.currentTimeMillis() - start;
        watcher.stop();
        writer.join();

        // Must have waited through the interim growth (>= 2 full 500ms*3 stability windows).
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2000);
        verify(releaseNormalizationService).normalize(any(Path.class), any(AcquisitionCategory.class));
    }

    @Test
    void rejectedNormalization_flipsMatchingJobToFailedWithReason() throws IOException {
        Path file = completedDownloadsDir.resolve("matching-release-title.zip");
        Files.writeString(file, "archive-bytes");

        AcquisitionJobEntity job = waitingJob(2L, "Matching Release Title");
        when(acquisitionJobRepository.findAllByStatusIn(anyList())).thenReturn(List.of(job));
        when(releaseNormalizationService.normalize(any(Path.class), any(AcquisitionCategory.class)))
                .thenReturn(new NormalizationResult.Rejected("Archive contains 0 candidate book files"));

        watcher.start();
        watcher.stop();

        assertThat(job.getStatus()).isEqualTo(AcquisitionJobEntity.Status.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("Archive contains 0 candidate book files");
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void readyNormalization_movesFileIntoBookdropAndFlipsJobToImporting() throws IOException {
        Path file = completedDownloadsDir.resolve("matched-file-title.epub");
        Files.writeString(file, "epub-bytes");

        AcquisitionJobEntity job = waitingJob(3L, "Matched File Title");
        when(acquisitionJobRepository.findAllByStatusIn(anyList())).thenReturn(List.of(job));
        when(releaseNormalizationService.normalize(any(Path.class), any(AcquisitionCategory.class)))
                .thenReturn(new NormalizationResult.Ready(List.of(file)));

        watcher.start();
        watcher.stop();

        Path movedFile = bookdropDir.resolve("matched-file-title.epub");
        assertThat(Files.exists(movedFile)).isTrue();
        assertThat(Files.exists(file)).isFalse();

        verify(bookdropEventHandlerService).enqueueFile(movedFile, StandardWatchEventKinds.ENTRY_CREATE);
        assertThat(job.getStatus()).isEqualTo(AcquisitionJobEntity.Status.IMPORTING);
    }

    @Test
    void nonMatchingFile_isIgnored_noJobTouchedAndFileLeftInPlace() throws IOException {
        Path file = completedDownloadsDir.resolve("totally-unrelated-download.epub");
        Files.writeString(file, "epub-bytes");

        AcquisitionJobEntity job = waitingJob(4L, "Something Completely Different Xyz");
        when(acquisitionJobRepository.findAllByStatusIn(anyList())).thenReturn(List.of(job));

        watcher.start();
        watcher.stop();

        assertThat(job.getStatus()).isEqualTo(AcquisitionJobEntity.Status.WAITING_FOR_FILE);
        assertThat(Files.exists(file)).isTrue();
        verify(releaseNormalizationService, never()).normalize(any(), any());
        verify(bookdropEventHandlerService, never()).enqueueFile(any(), any());
    }

    // ---- matchScore() pure-method coverage ----

    @Test
    void matchScore_matchesRealisticFilenameAgainstDottedReleaseTitle() {
        double score = AcquisitionFolderWatcherService.matchScore(
                "Some Book Title (2024).epub", "Some.Book.Title.2024.EPUB");

        assertThat(score).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void matchScore_doesNotMatchUnrelatedTitle() {
        double score = AcquisitionFolderWatcherService.matchScore(
                "The Great Gatsby.epub", "Dune");

        assertThat(score).isLessThan(0.6);
    }

    @Test
    void matchScore_matchesAudiobookFolderNameAgainstReleaseTitle() {
        double score = AcquisitionFolderWatcherService.matchScore(
                "Project Hail Mary - Andy Weir [M4B]", "Project Hail Mary");

        assertThat(score).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void matchScore_partialOverlapBelowThreshold_isNotAMatch() {
        double score = AcquisitionFolderWatcherService.matchScore(
                "Foundation.epub", "Foundation and Empire Isaac Asimov Collection");

        assertThat(score).isLessThan(0.6);
    }
}
