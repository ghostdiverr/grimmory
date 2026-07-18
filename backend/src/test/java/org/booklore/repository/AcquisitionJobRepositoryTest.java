package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.service.task.TaskCronService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = {
        BookloreApplication.class
})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:acquisitionjobtestdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false",
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
@Import(AcquisitionJobRepositoryTest.TestConfig.class)
class AcquisitionJobRepositoryTest {

    @Autowired
    private AcquisitionJobRepository acquisitionJobRepository;

    @PersistenceContext
    private EntityManager entityManager;

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

    private AcquisitionJobEntity newJob(AcquisitionJobEntity.Status status) {
        return AcquisitionJobEntity.builder()
                .query("Foundation")
                .category(AcquisitionCategory.BOOK)
                .releaseTitle("Foundation - Isaac Asimov")
                .releaseGuid("guid-" + status)
                .indexerId(7L)
                .indexerName("SomeIndexer")
                .protocol("torrent")
                .sizeBytes(123456L)
                .status(status)
                .requestedByUserId(1L)
                .grabbedAt(Instant.now())
                .build();
    }

    @Test
    void contextLoads() {
        assertThat(acquisitionJobRepository).isNotNull();
    }

    @Test
    void saveAndReload_roundTripsAllFields() {
        AcquisitionJobEntity job = newJob(AcquisitionJobEntity.Status.GRABBED);
        job.setBookId(10L);
        job.setLibraryId(20L);

        AcquisitionJobEntity saved = acquisitionJobRepository.saveAndFlush(job);
        entityManager.clear();

        Optional<AcquisitionJobEntity> found = acquisitionJobRepository.findById(saved.getId());

        assertThat(found).isPresent();
        AcquisitionJobEntity reloaded = found.get();
        assertThat(reloaded.getBookId()).isEqualTo(10L);
        assertThat(reloaded.getLibraryId()).isEqualTo(20L);
        assertThat(reloaded.getQuery()).isEqualTo("Foundation");
        assertThat(reloaded.getCategory()).isEqualTo(AcquisitionCategory.BOOK);
        assertThat(reloaded.getReleaseTitle()).isEqualTo("Foundation - Isaac Asimov");
        assertThat(reloaded.getReleaseGuid()).isEqualTo("guid-GRABBED");
        assertThat(reloaded.getIndexerId()).isEqualTo(7L);
        assertThat(reloaded.getIndexerName()).isEqualTo("SomeIndexer");
        assertThat(reloaded.getProtocol()).isEqualTo("torrent");
        assertThat(reloaded.getSizeBytes()).isEqualTo(123456L);
        assertThat(reloaded.getStatus()).isEqualTo(AcquisitionJobEntity.Status.GRABBED);
        assertThat(reloaded.getRequestedByUserId()).isEqualTo(1L);
        assertThat(reloaded.getGrabbedAt()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void findAllByStatusIn_returnsOnlyMatchingRows() {
        acquisitionJobRepository.saveAndFlush(newJob(AcquisitionJobEntity.Status.GRABBED));
        acquisitionJobRepository.saveAndFlush(newJob(AcquisitionJobEntity.Status.WAITING_FOR_FILE));
        acquisitionJobRepository.saveAndFlush(newJob(AcquisitionJobEntity.Status.COMPLETED));

        List<AcquisitionJobEntity> active = acquisitionJobRepository.findAllByStatusIn(
                List.of(AcquisitionJobEntity.Status.GRABBED, AcquisitionJobEntity.Status.WAITING_FOR_FILE));

        assertThat(active).hasSize(2);
        assertThat(active).extracting(AcquisitionJobEntity::getStatus)
                .containsExactlyInAnyOrder(AcquisitionJobEntity.Status.GRABBED, AcquisitionJobEntity.Status.WAITING_FOR_FILE);
    }

    @Test
    void findAllByOrderByCreatedAtDesc_returnsAllRows() {
        acquisitionJobRepository.saveAndFlush(newJob(AcquisitionJobEntity.Status.GRABBED));
        acquisitionJobRepository.saveAndFlush(newJob(AcquisitionJobEntity.Status.FAILED));

        List<AcquisitionJobEntity> jobs = acquisitionJobRepository.findAllByOrderByCreatedAtDesc();

        assertThat(jobs).hasSize(2);
    }

    @Test
    void requiredColumns_rejectMissingNotNullValues() {
        AcquisitionJobEntity invalid = AcquisitionJobEntity.builder()
                .category(AcquisitionCategory.BOOK)
                .releaseTitle("Foundation")
                .releaseGuid("guid-x")
                .indexerId(7L)
                .status(AcquisitionJobEntity.Status.GRABBED)
                .requestedByUserId(1L)
                .grabbedAt(Instant.now())
                // query intentionally omitted (NOT NULL column)
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> acquisitionJobRepository.saveAndFlush(invalid))
                .isInstanceOf(Exception.class);
    }
}
