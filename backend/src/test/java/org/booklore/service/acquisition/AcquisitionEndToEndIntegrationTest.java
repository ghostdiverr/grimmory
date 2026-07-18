package org.booklore.service.acquisition;

import org.booklore.BookloreApplication;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.acquisition.GrabRequest;
import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.dto.settings.AppSettingKey;
import org.booklore.model.dto.settings.ProwlarrSettings;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.entity.AppSettingEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookdropFileEntity;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.repository.AppSettingsRepository;
import org.booklore.repository.BookdropFileRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Full-context wiring test for the Prowlarr acquisition feature: exercises
 * {@link AcquisitionService#grab(GrabRequest)} against a real H2-backed Spring context, then
 * drives {@link AcquisitionFolderWatcherService} (a real {@code SmartLifecycle} bean, real
 * watch thread, real {@link org.booklore.service.acquisition.normalize.ReleaseNormalizationService})
 * against real {@code @TempDir} folders standing in for the Prowlarr completed-downloads folder
 * and the BookDrop ingest folder, and confirms the acquisition job and the hand-off to BookDrop
 * both progress through a real {@code BookdropEventHandlerService} worker thread.
 *
 * <p><b>Why {@code @SpringBootTest}:</b> this is the one acquisition-feature scenario that
 * requires the full Spring context — a real {@code SmartLifecycle} start()/stop() cycle, a real
 * transactional {@link AcquisitionJobRepository}, and the real {@code BookdropEventHandlerService}
 * background worker thread cooperating across real filesystem watches. None of that can be
 * exercised with plain unit tests (see {@link AcquisitionServiceTest} and
 * {@link AcquisitionFolderWatcherServiceTest} for the mocked-collaborator unit coverage of the
 * individual pieces).
 *
 * <p><b>Only one prior {@code @SpringBootTest}+H2 pattern exists in this repo</b> (see
 * {@code AcquisitionJobRepositoryTest}, since this codebase's only {@code @DataJpaTest}/
 * Testcontainers precedent was commented out); this test follows the same
 * {@code @TestPropertySource}+mocked-{@code Flyway}/{@code TaskCronService} shape for
 * consistency.
 *
 * <p><b>Judgment call — why {@code app.bookdrop-folder} is a fixed path, not {@code @TempDir}:</b>
 * {@code AppProperties} is a {@code @ConfigurationProperties} bean bound once at context
 * refresh, before any {@code @TempDir} instance field exists, so its value must be a
 * {@code @TestPropertySource} compile-time constant. The completed-downloads folder has no such
 * constraint — {@link org.booklore.model.dto.settings.ProwlarrSettings} is a JSON app-setting
 * row read at {@code SmartLifecycle.start()} time (not a {@code @Value}/{@code @ConfigurationProperties}
 * binding) — so it can safely be a real {@code @TempDir}, seeded into the settings table after
 * the context is already up.
 *
 * <p><b>Judgment call — settings must exist before {@code start()}:</b>
 * {@link AcquisitionFolderWatcherService#start()} reads {@code ProwlarrSettings} exactly once,
 * at {@code SmartLifecycle} startup, which happens automatically during context refresh — before
 * this test method gets a chance to seed the completed-downloads folder setting. Rather than
 * fighting that ordering with a {@code @DynamicPropertySource} hack (settings are DB rows, not
 * Spring properties, so that mechanism doesn't apply anyway), this test lets the initial
 * automatic {@code start()} run and disable itself (no folder configured yet), then seeds the
 * setting and calls {@code stop()}/{@code start()} on the bean manually — the less invasive of
 * the two options the task description called out.
 *
 * <p><b>Judgment call — where the test stops asserting ({@code IMPORTING}, not
 * {@code COMPLETED}):</b> {@code BookdropEventHandlerService.processFile} always leaves a new
 * file at {@code BookdropFileEntity.Status.PENDING_REVIEW} — turning a BookDrop file into a real
 * {@code BookEntity} (and thus completing the acquisition job via the
 * {@code BookAddedEvent} correlation in {@code AcquisitionFolderWatcherService#onBookAdded})
 * requires a separate, explicit "accept from BookDrop" action that is a different feature
 * entirely and out of scope for this wiring test. So {@code IMPORTING} (the acquisition job
 * side) plus a {@code PENDING_REVIEW} {@code BookdropFileEntity} (the BookDrop hand-off side) is
 * the honest, real endpoint of the pipeline this test can drive automatically. To keep the
 * scenario deterministic and free of network calls, {@code METADATA_DOWNLOAD_ON_BOOKDROP} is
 * explicitly turned off — otherwise a title-bearing fixture would trigger real online
 * metadata-provider lookups (Amazon/Google/GoodReads) from {@code BookdropMetadataService}.
 *
 * <p>The fixture is a real, minimal, valid EPUB (mimetype + container.xml + a real OPF with a
 * title) built the same way {@code EpubMetadataExtractorTest} does — the extraction path for
 * EPUB is plain zip+XML parsing with no network/native dependency, so a real fixture costs
 * almost nothing here and lets the test also confirm the moved file is real, readable EPUB
 * content rather than an opaque stub.
 */
@SpringBootTest(classes = BookloreApplication.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:acquisitione2edb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/acquisition-e2e-config",
        "app.bookdrop-folder=build/tmp/acquisition-e2e-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
@Import(AcquisitionEndToEndIntegrationTest.TestConfig.class)
class AcquisitionEndToEndIntegrationTest {

    private static final Path BOOKDROP_FOLDER = Path.of("build/tmp/acquisition-e2e-bookdrop");
    private static final String RELEASE_TITLE = "Test Book Title 2024";
    private static final String FIXTURE_FILE_NAME = "test-book-title-2024.epub";

    @Autowired
    private AcquisitionService acquisitionService;
    @Autowired
    private AcquisitionFolderWatcherService acquisitionFolderWatcherService;
    @Autowired
    private AcquisitionJobRepository acquisitionJobRepository;
    @Autowired
    private BookdropFileRepository bookdropFileRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppSettingsRepository appSettingsRepository;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AuthenticationService authenticationService;
    @MockitoBean
    private ProwlarrClient prowlarrClient;

    @TempDir
    Path completedDownloadsDir;

    @TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public Flyway flyway() {
            return mock(Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    @Test
    void grabbedRelease_isMatchedNormalizedAndHandedOffToBookdrop() throws IOException {
        // BOOKDROP_FOLDER is a fixed path (required to be a @TestPropertySource constant, see
        // class javadoc), not a @TempDir, so it survives across test runs on the same machine.
        // Clean up any leftover files from a previous run before this run's job IDs (which
        // restart at 1 each run, since the H2 schema is create-drop) can collide with them.
        cleanBookdropFolder();

        // ---- 1. authenticated user, real row so downstream username lookups resolve cleanly ----
        BookLoreUserEntity userEntity = userRepository.save(BookLoreUserEntity.builder()
                .username("acquisition-e2e-user")
                .passwordHash("x")
                .name("Acquisition E2E")
                .build());

        BookLoreUser.UserPermissions permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(true);
        BookLoreUser authenticatedUser = BookLoreUser.builder()
                .id(userEntity.getId())
                .username(userEntity.getUsername())
                .permissions(permissions)
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(authenticatedUser);

        ProwlarrReleaseDto release = new ProwlarrReleaseDto(
                "guid-e2e-1", 42L, "TestIndexer", RELEASE_TITLE, 123_456L, 5, 1,
                "2024-01-01", "http://example.invalid/download/1", "torrent", AcquisitionCategory.BOOK
        );
        when(prowlarrClient.search(anyString(), any())).thenReturn(List.of(release));

        // ---- 2. grab() persists a job and — per WP4's two-step transition — leaves it WAITING_FOR_FILE ----
        GrabRequest request = new GrabRequest(release, "Test Book Title", null, null);
        AcquisitionJobDto grabbedJob = acquisitionService.grab(request);

        assertThat(grabbedJob.getStatus()).isEqualTo(AcquisitionJobEntity.Status.WAITING_FOR_FILE);
        Long jobId = grabbedJob.getId();
        assertThat(acquisitionJobRepository.findById(jobId)).isPresent();

        // ---- 3. seed the JSON ProwlarrSettings app-setting pointing at the real @TempDir folder ----
        ProwlarrSettings prowlarrSettings = ProwlarrSettings.builder()
                .enabled(true)
                .completedDownloadsFolder(completedDownloadsDir.toString())
                .build();
        seedSetting(AppSettingKey.PROWLARR_SETTINGS, objectMapper.writeValueAsString(prowlarrSettings));
        // Keep BookDrop metadata fetching offline/deterministic — our fixture has a real,
        // non-filename-derived title, which would otherwise trigger real online
        // metadata-provider lookups from BookdropMetadataService.
        seedSetting(AppSettingKey.METADATA_DOWNLOAD_ON_BOOKDROP, "false");
        cacheManager.getCache("appSettings").clear();

        // ---- 4. drop a real, minimal, valid EPUB fixture into the completed-downloads folder ----
        Path fixtureFile = completedDownloadsDir.resolve(FIXTURE_FILE_NAME);
        createMinimalEpub(fixtureFile, RELEASE_TITLE);

        // ---- 5. AcquisitionFolderWatcherService.start() only reads settings/folder once, at
        // SmartLifecycle startup (already run automatically — and disabled itself, since no
        // folder was configured yet — during context refresh). Restart it manually now that the
        // setting and the fixture file are both in place; this is the less invasive of the two
        // options for handling that ordering constraint, versus fighting Spring context refresh
        // ordering with a settings-seeding hook.
        acquisitionFolderWatcherService.stop();
        acquisitionFolderWatcherService.start();

        // ---- 6. the watcher's own file-stability wait + match + normalize + move-into-bookdrop
        // all run synchronously inside start()'s initial rescan, but poll anyway rather than
        // assume that timing.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var current = acquisitionJobRepository.findById(jobId);
                    assertThat(current).isPresent();
                    assertThat(current.get().getStatus()).isEqualTo(AcquisitionJobEntity.Status.IMPORTING);
                });

        Path movedFile = BOOKDROP_FOLDER.resolve(FIXTURE_FILE_NAME);
        assertThat(Files.exists(fixtureFile)).isFalse();
        assertThat(Files.exists(movedFile)).isTrue();

        // ---- 7. BookdropEventHandlerService's own worker thread (already running since initial
        // context startup) picks the moved file up asynchronously; wait for its PENDING_REVIEW row.
        // NOTE: Awaitility's untilAsserted() only retries on AssertionError, not on arbitrary
        // exceptions (e.g. Optional#orElseThrow()'s NoSuchElementException) — so the "not there
        // yet" case must be expressed as a failed AssertJ assertion, not a thrown exception, or
        // the very first poll (before the row exists) fails the test immediately instead of
        // retrying for the full timeout.
        // originalMetadata is @Basic(fetch = LAZY), and the repository call's own transaction
        // (and persistence context) closes as soon as findByFilePath returns — so the lazy read
        // must happen inside the same transaction as the fetch, not after it.
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> transactionTemplate.executeWithoutResult(status -> {
                    var found = bookdropFileRepository.findByFilePath(movedFile.toAbsolutePath().toString());
                    assertThat(found).isPresent();
                    BookdropFileEntity bookdropFile = found.get();
                    assertThat(bookdropFile.getStatus()).isEqualTo(BookdropFileEntity.Status.PENDING_REVIEW);
                    assertThat(bookdropFile.getFileName()).isEqualTo(FIXTURE_FILE_NAME);
                    assertThat(bookdropFile.getOriginalMetadata()).contains(RELEASE_TITLE);
                }));
    }

    private void cleanBookdropFolder() throws IOException {
        if (!Files.exists(BOOKDROP_FOLDER)) {
            return;
        }
        try (var stream = Files.walk(BOOKDROP_FOLDER)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(BOOKDROP_FOLDER))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    private void seedSetting(AppSettingKey key, String rawValue) {
        AppSettingEntity entity = appSettingsRepository.findByName(key.toString());
        if (entity == null) {
            entity = new AppSettingEntity();
            entity.setName(key.toString());
        }
        entity.setVal(rawValue);
        appSettingsRepository.save(entity);
    }

    /**
     * Builds a real, minimal, valid EPUB — same shape as {@code EpubMetadataExtractorTest}'s
     * {@code createEpub} helper (mimetype + META-INF/container.xml + a real OPF with a
     * {@code dc:title}) — so the BookDrop metadata-extraction step reads a genuine title instead
     * of falling back to the filename.
     */
    private void createMinimalEpub(Path target, String title) throws IOException {
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>%s</dc:title>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest/>
                </package>""".formatted(title);

        String containerXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>""";

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(containerXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opf.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
