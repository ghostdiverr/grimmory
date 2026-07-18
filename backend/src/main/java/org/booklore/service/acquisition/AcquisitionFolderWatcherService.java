package org.booklore.service.acquisition;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.mapper.AcquisitionJobMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.settings.ProwlarrSettings;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.acquisition.normalize.NormalizationResult;
import org.booklore.service.acquisition.normalize.ReleaseNormalizationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.bookdrop.BookdropEventHandlerService;
import org.booklore.service.event.BookAddedEvent;
import org.booklore.util.FileStabilityChecker;
import org.booklore.util.FileUtils;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Watches the Prowlarr completed-downloads folder for finished downloads, matches them
 * against acquisition jobs waiting for their file, normalizes them into BookDrop-ready
 * file(s), and hands them off to {@link BookdropEventHandlerService} for the rest of the
 * existing ingestion pipeline.
 *
 * <p>Structurally mirrors {@code BookdropMonitoringService}'s {@link SmartLifecycle}
 * watch-folder pattern, but registers up to three watch points (root, book subfolder,
 * audiobook subfolder) so a completed release can be attributed to a category.
 */
@Slf4j
@Service
public class AcquisitionFolderWatcherService implements SmartLifecycle {

    private static final int LIFECYCLE_PHASE = 30;

    private static final long STABILITY_CHECK_INTERVAL_MS = 500;
    private static final int STABILITY_REQUIRED_CHECKS = 3;
    private static final long STABILITY_MAX_WAIT_MS = 30_000;

    private static final double MATCH_THRESHOLD = 0.6;
    private static final String TEMP_UNZIP_DIR_MARKER = "grimmory-acquisition-unzip-";
    private static final Pattern NON_ALPHANUMERIC_RUN = Pattern.compile("[^a-z0-9]+");

    private final AppProperties appProperties;
    private final AppSettingService appSettingService;
    private final AcquisitionJobRepository acquisitionJobRepository;
    private final AcquisitionJobMapper acquisitionJobMapper;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final ReleaseNormalizationService releaseNormalizationService;
    private final BookdropEventHandlerService bookdropEventHandlerService;

    /**
     * Filename (as reported by {@code Book.getPrimaryFile().getFileName()}) -> acquisition job id,
     * for files handed off to BookDrop and awaiting {@link BookAddedEvent} correlation.
     *
     * <p><b>v1 limitation:</b> this map is in-memory only and is lost on app restart. A job
     * stuck in IMPORTING across a restart will never auto-complete via this correlation path;
     * it will eventually be flipped to TIMED_OUT by {@code AcquisitionQueueScanTask}'s sweep.
     */
    private final Map<String, Long> pendingImports = new ConcurrentHashMap<>();

    private final Map<WatchKey, WatchTarget> watchTargets = new ConcurrentHashMap<>();
    private final Lock monitorLock = new ReentrantLock();

    private Path rootFolder;
    private Path bookFolder;
    private Path audiobookFolder;
    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean disabled;

    public AcquisitionFolderWatcherService(
            AppProperties appProperties,
            AppSettingService appSettingService,
            AcquisitionJobRepository acquisitionJobRepository,
            AcquisitionJobMapper acquisitionJobMapper,
            NotificationService notificationService,
            UserRepository userRepository,
            ReleaseNormalizationService releaseNormalizationService,
            BookdropEventHandlerService bookdropEventHandlerService
    ) {
        this.appProperties = appProperties;
        this.appSettingService = appSettingService;
        this.acquisitionJobRepository = acquisitionJobRepository;
        this.acquisitionJobMapper = acquisitionJobMapper;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.releaseNormalizationService = releaseNormalizationService;
        this.bookdropEventHandlerService = bookdropEventHandlerService;
    }

    @Override
    public void start() {
        ProwlarrSettings settings = appSettingService.getAppSettings().getProwlarrSettings();
        if (settings == null || !settings.isEnabled()) {
            log.warn("Prowlarr is disabled. Acquisition folder watcher is disabled.");
            disabled = true;
            return;
        }

        String completedDownloadsFolder = settings.getCompletedDownloadsFolder();
        if (completedDownloadsFolder == null || completedDownloadsFolder.isBlank()) {
            log.warn("Prowlarr completed-downloads folder is not configured. Acquisition folder watcher is disabled.");
            disabled = true;
            return;
        }

        Path candidateRoot = Path.of(completedDownloadsFolder);
        if (!Files.isDirectory(candidateRoot)) {
            log.warn("Configured completed-downloads folder does not exist: '{}'. Acquisition folder watcher is disabled.", candidateRoot);
            disabled = true;
            return;
        }

        rootFolder = candidateRoot;
        bookFolder = resolveSubfolder(rootFolder, settings.getBookSubfolder());
        audiobookFolder = resolveSubfolder(rootFolder, settings.getAudiobookSubfolder());

        try {
            log.info("Starting acquisition completed-downloads folder monitor: {}", rootFolder);
            watchService = FileSystems.getDefault().newWatchService();
            registerWatches();
            running = true;
            paused = false;
            watchThread = new Thread(this::processEvents, "AcquisitionFolderWatcher");
            watchThread.setDaemon(true);
            watchThread.start();
            // Must be reset before rescanCompletedFolder() runs: that method's own disabled-guard
            // would otherwise see the stale `true` left by a prior disabled start() attempt and
            // skip the rescan even though the watcher just finished starting successfully.
            disabled = false;
            rescanCompletedFolder();
        } catch (IOException e) {
            log.warn("Failed to start acquisition folder monitor. Acquisition folder watcher is disabled.", e);
            disabled = true;
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        log.info("Stopping acquisition folder monitor...");
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(5000);
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for AcquisitionFolderWatcher thread to stop");
                Thread.currentThread().interrupt();
            }
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.error("Error closing WatchService", e);
            }
        }
        log.info("Stopped acquisition folder monitor");
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    public void pauseMonitoring() {
        if (disabled) return;
        monitorLock.lock();
        try {
            if (!paused) {
                watchTargets.keySet().forEach(WatchKey::cancel);
                watchTargets.clear();
                paused = true;
                log.info("Acquisition folder monitoring paused.");
            } else {
                log.info("Acquisition folder monitoring already paused.");
            }
        } finally {
            monitorLock.unlock();
        }
    }

    public void resumeMonitoring() {
        if (disabled) return;
        monitorLock.lock();
        try {
            if (paused) {
                try {
                    registerWatches();
                    paused = false;
                    log.info("Acquisition folder monitoring resumed.");
                } catch (IOException e) {
                    log.error("Error reregistering acquisition folder watches during resume", e);
                }
            } else {
                log.info("Acquisition folder monitoring is not paused, cannot resume.");
            }
        } finally {
            monitorLock.unlock();
        }
    }

    /**
     * Re-walks the watched folder(s) for entries that arrived while the app was down, using
     * the same stability+match+normalize path as the live watcher. Called by
     * {@code AcquisitionQueueScanTask}.
     */
    public void rescanCompletedFolder() {
        if (disabled) {
            log.warn("Acquisition folder watcher is disabled. Skipping rescan.");
            return;
        }
        log.info("Rescan of acquisition completed-downloads folder triggered.");
        scanFolder(rootFolder, Optional.empty());
        if (bookFolder != null) {
            scanFolder(bookFolder, Optional.of(AcquisitionCategory.BOOK));
        }
        if (audiobookFolder != null) {
            scanFolder(audiobookFolder, Optional.of(AcquisitionCategory.AUDIOBOOK));
        }
    }

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBookAdded(BookAddedEvent event) {
        Book book = event.book();
        BookFile primaryFile = book.getPrimaryFile();
        if (primaryFile == null || primaryFile.getFileName() == null) {
            return;
        }

        Long jobId = pendingImports.remove(primaryFile.getFileName());
        if (jobId == null) {
            return;
        }

        acquisitionJobRepository.findById(jobId).ifPresentOrElse(job -> {
            job.setStatus(AcquisitionJobEntity.Status.COMPLETED);
            job.setCompletedAt(Instant.now());
            if (job.getBookId() == null) {
                job.setBookId(book.getId());
            }
            AcquisitionJobEntity saved = acquisitionJobRepository.save(job);
            log.info("Acquisition job {} completed, book id {}", saved.getId(), saved.getBookId());
            publishUpdate(saved);
        }, () -> log.warn("Acquisition job {} not found while completing book-added correlation for file '{}'",
                jobId, primaryFile.getFileName()));
    }

    // ---- watch registration / event loop ----

    private Path resolveSubfolder(Path root, String subfolder) {
        if (subfolder == null || subfolder.isBlank()) {
            return null;
        }
        Path resolved = root.resolve(subfolder);
        if (!Files.isDirectory(resolved)) {
            log.warn("Configured acquisition subfolder '{}' does not exist under '{}', ignoring.", subfolder, root);
            return null;
        }
        return resolved;
    }

    private void registerWatches() throws IOException {
        watchTargets.clear();

        WatchKey rootKey = rootFolder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        watchTargets.put(rootKey, new WatchTarget(rootFolder, Optional.empty()));

        if (bookFolder != null) {
            WatchKey bookKey = bookFolder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            watchTargets.put(bookKey, new WatchTarget(bookFolder, Optional.of(AcquisitionCategory.BOOK)));
        }
        if (audiobookFolder != null) {
            WatchKey audiobookKey = audiobookFolder.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            watchTargets.put(audiobookKey, new WatchTarget(audiobookFolder, Optional.of(AcquisitionCategory.AUDIOBOOK)));
        }
    }

    private void processEvents() {
        while (running) {
            if (paused) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.info("Acquisition folder watcher thread interrupted during pause");
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                log.info("Acquisition folder watcher thread interrupted");
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                log.info("WatchService closed, stopping thread");
                return;
            }

            WatchTarget target = watchTargets.get(key);
            if (target == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    log.warn("Overflow event detected on acquisition folder watcher");
                    continue;
                }

                Path context = (Path) event.context();
                Path fullPath = target.path().resolve(context);
                log.info("Detected {} event on: {}", kind.name(), fullPath);
                handleNewEntry(fullPath, target.category());
            }

            boolean valid = key.reset();
            if (!valid) {
                log.warn("Acquisition watch key is no longer valid for {}", target.path());
            }
        }
    }

    private void scanFolder(Path folder, Optional<AcquisitionCategory> category) {
        List<Path> entries;
        try (var stream = Files.list(folder)) {
            entries = stream.filter(p -> !FileUtils.shouldIgnore(p)).toList();
        } catch (IOException e) {
            log.error("Error scanning acquisition completed-downloads folder: {}", folder, e);
            return;
        }
        entries.forEach(entry -> handleNewEntry(entry, category));
    }

    // ---- matching / normalization / hand-off ----

    private void handleNewEntry(Path path, Optional<AcquisitionCategory> category) {
        if (FileUtils.shouldIgnore(path) || Files.notExists(path)) {
            return;
        }
        if (!waitForStability(path)) {
            log.warn("Completed-download entry did not stabilize within timeout, skipping: {}", path);
            return;
        }
        processStableEntry(path, category);
    }

    private boolean waitForStability(Path path) {
        if (Files.isDirectory(path)) {
            return waitForDirectoryStability(path);
        }
        return FileStabilityChecker.waitForStability(path, STABILITY_CHECK_INTERVAL_MS, STABILITY_REQUIRED_CHECKS, STABILITY_MAX_WAIT_MS);
    }

    private boolean waitForDirectoryStability(Path dir) {
        long startTime = System.currentTimeMillis();
        long lastSize = -1;
        int stableCount = 0;

        while (System.currentTimeMillis() - startTime < STABILITY_MAX_WAIT_MS) {
            if (Files.notExists(dir)) {
                return false;
            }
            Long sizeKb = FileUtils.getFolderSizeInKb(dir);
            long currentSize = sizeKb == null ? -1 : sizeKb;

            if (currentSize == lastSize && currentSize >= 0) {
                stableCount++;
                if (stableCount >= STABILITY_REQUIRED_CHECKS) {
                    return true;
                }
            } else {
                stableCount = 0;
            }

            lastSize = currentSize;
            try {
                Thread.sleep(STABILITY_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("Folder size did not stabilize after {}ms: {}", STABILITY_MAX_WAIT_MS, dir);
        return false;
    }

    private void processStableEntry(Path path, Optional<AcquisitionCategory> category) {
        List<AcquisitionCategory> candidateCategories = category
                .map(List::of)
                .orElse(List.of(AcquisitionCategory.BOOK, AcquisitionCategory.AUDIOBOOK));

        List<AcquisitionJobEntity> candidateJobs = acquisitionJobRepository
                .findAllByStatusIn(List.of(AcquisitionJobEntity.Status.WAITING_FOR_FILE)).stream()
                .filter(job -> candidateCategories.contains(job.getCategory()))
                .toList();

        String fileName = path.getFileName().toString();

        AcquisitionJobEntity bestMatch = null;
        double bestScore = 0.0;
        for (AcquisitionJobEntity job : candidateJobs) {
            double score = matchScore(fileName, job.getReleaseTitle());
            if (score < MATCH_THRESHOLD) {
                continue;
            }
            boolean better = bestMatch == null
                    || score > bestScore
                    || (score == bestScore && isEarlier(job.getGrabbedAt(), bestMatch.getGrabbedAt()));
            if (better) {
                bestMatch = job;
                bestScore = score;
            }
        }

        if (bestMatch == null) {
            log.debug("No acquisition job matched completed-download entry: {}", path);
            return;
        }

        handleMatch(bestMatch, path);
    }

    private boolean isEarlier(Instant candidate, Instant current) {
        if (candidate == null) return false;
        if (current == null) return true;
        return candidate.isBefore(current);
    }

    private void handleMatch(AcquisitionJobEntity job, Path path) {
        job.setStatus(AcquisitionJobEntity.Status.NORMALIZING);
        job = acquisitionJobRepository.save(job);
        publishUpdate(job);

        NormalizationResult result = releaseNormalizationService.normalize(path, job.getCategory());

        if (result instanceof NormalizationResult.Rejected rejected) {
            job.setStatus(AcquisitionJobEntity.Status.FAILED);
            job.setErrorMessage(rejected.reason());
            job.setCompletedAt(Instant.now());
            job = acquisitionJobRepository.save(job);
            log.info("Acquisition job {} rejected during normalization: {}", job.getId(), rejected.reason());
            publishUpdate(job);
            return;
        }

        NormalizationResult.Ready ready = (NormalizationResult.Ready) result;
        Path bookdropFolder = Path.of(appProperties.getBookdropFolder());
        Set<Path> tempDirsToClean = new HashSet<>();

        for (Path readyFile : ready.bookdropReadyFiles()) {
            Path movedPath;
            try {
                movedPath = moveIntoBookdrop(readyFile, bookdropFolder, job.getId());
            } catch (IOException e) {
                log.error("Failed to move normalized file {} into bookdrop folder", readyFile, e);
                job.setStatus(AcquisitionJobEntity.Status.FAILED);
                job.setErrorMessage("Failed to move normalized file into bookdrop folder: " + e.getMessage());
                job.setCompletedAt(Instant.now());
                job = acquisitionJobRepository.save(job);
                publishUpdate(job);
                return;
            }

            if (readyFile.toString().contains(TEMP_UNZIP_DIR_MARKER)) {
                tempDirsToClean.add(readyFile.getParent());
            }

            pendingImports.put(movedPath.getFileName().toString(), job.getId());
            bookdropEventHandlerService.enqueueFile(movedPath, StandardWatchEventKinds.ENTRY_CREATE);
        }

        for (Path tempDir : tempDirsToClean) {
            try {
                FileUtils.deleteDirectoryRecursively(tempDir);
            } catch (IOException e) {
                log.warn("Failed to clean up temp unzip directory {}", tempDir, e);
            }
        }

        job.setStatus(AcquisitionJobEntity.Status.IMPORTING);
        job = acquisitionJobRepository.save(job);
        log.info("Acquisition job {} handed off to BookDrop, awaiting import completion", job.getId());
        publishUpdate(job);
    }

    private Path moveIntoBookdrop(Path source, Path bookdropFolder, Long jobId) throws IOException {
        String fileName = source.getFileName().toString();
        Path target = bookdropFolder.resolve(fileName);
        if (Files.exists(target)) {
            target = bookdropFolder.resolve(jobId + "_" + fileName);
        }
        return Files.move(source, target);
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

    // ---- fuzzy matching (pure, unit-testable) ----

    /**
     * Fraction of the release title's significant tokens (length &gt; 2) that appear as a
     * substring of the normalized filename. Both inputs are lowercased, have any file
     * extension stripped, and have non-alphanumeric runs collapsed to single spaces before
     * comparison.
     */
    public static double matchScore(String fileName, String releaseTitle) {
        String normalizedFileName = normalize(fileName);
        String normalizedTitle = normalize(releaseTitle);

        List<String> significantTokens = List.of(normalizedTitle.split(" ")).stream()
                .filter(token -> token.length() > 2)
                .toList();

        if (significantTokens.isEmpty()) {
            return 0.0;
        }

        long matchedCount = significantTokens.stream()
                .filter(normalizedFileName::contains)
                .count();

        return (double) matchedCount / significantTokens.size();
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String lower = input.toLowerCase(Locale.ROOT);
        lower = stripExtension(lower);
        lower = NON_ALPHANUMERIC_RUN.matcher(lower).replaceAll(" ");
        return lower.trim();
    }

    private static String stripExtension(String lowerCaseInput) {
        int dot = lowerCaseInput.lastIndexOf('.');
        if (dot <= 0) {
            return lowerCaseInput;
        }
        String suffix = lowerCaseInput.substring(dot + 1);
        if (suffix.isEmpty() || suffix.length() > 5) {
            return lowerCaseInput;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isLetterOrDigit(suffix.charAt(i))) {
                return lowerCaseInput;
            }
        }
        return lowerCaseInput.substring(0, dot);
    }

    private record WatchTarget(Path path, Optional<AcquisitionCategory> category) {
    }
}
