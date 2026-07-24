package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.entity.WantedBookEntity.Status;
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
        "spring.datasource.url=jdbc:h2:mem:wantedbooktestdb;DB_CLOSE_DELAY=-1",
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
@Import(WantedBookRepositoryTest.TestConfig.class)
class WantedBookRepositoryTest {

    @Autowired
    private WantedBookRepository wantedBookRepository;

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

    private WantedBookEntity newEntry(Status status) {
        return WantedBookEntity.builder()
                .title("Foundation")
                .author("Isaac Asimov")
                .category(AcquisitionCategory.BOOK)
                .libraryId(3L)
                .status(status)
                .requestedByUserId(1L)
                .build();
    }

    @Test
    void contextLoads() {
        assertThat(wantedBookRepository).isNotNull();
    }

    @Test
    void saveAndReload_roundTripsAllFields() {
        WantedBookEntity entry = newEntry(Status.ACTIVE);
        entry.setIsbn("9780553293357");
        entry.setExcludedGuids("guid-1,guid-2");

        WantedBookEntity saved = wantedBookRepository.saveAndFlush(entry);
        entityManager.clear();

        Optional<WantedBookEntity> found = wantedBookRepository.findById(saved.getId());

        assertThat(found).isPresent();
        WantedBookEntity reloaded = found.get();
        assertThat(reloaded.getTitle()).isEqualTo("Foundation");
        assertThat(reloaded.getAuthor()).isEqualTo("Isaac Asimov");
        assertThat(reloaded.getIsbn()).isEqualTo("9780553293357");
        assertThat(reloaded.getCategory()).isEqualTo(AcquisitionCategory.BOOK);
        assertThat(reloaded.getLibraryId()).isEqualTo(3L);
        assertThat(reloaded.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(reloaded.getExcludedGuids()).isEqualTo("guid-1,guid-2");
        assertThat(reloaded.getRequestedByUserId()).isEqualTo(1L);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void findAllByStatus_returnsOnlyMatchingRows() {
        wantedBookRepository.saveAndFlush(newEntry(Status.ACTIVE));
        wantedBookRepository.saveAndFlush(newEntry(Status.GRABBED));
        wantedBookRepository.saveAndFlush(newEntry(Status.ACTIVE));

        List<WantedBookEntity> active = wantedBookRepository.findAllByStatus(Status.ACTIVE);

        assertThat(active).hasSize(2);
        assertThat(active).extracting(WantedBookEntity::getStatus).containsOnly(Status.ACTIVE);
    }

    @Test
    void findAllByOrderByCreatedAtDesc_returnsAllRows() {
        wantedBookRepository.saveAndFlush(newEntry(Status.ACTIVE));
        wantedBookRepository.saveAndFlush(newEntry(Status.PAUSED));

        List<WantedBookEntity> entries = wantedBookRepository.findAllByOrderByCreatedAtDesc();

        assertThat(entries).hasSize(2);
    }

    @Test
    void requiredColumns_rejectMissingNotNullValues() {
        WantedBookEntity invalid = WantedBookEntity.builder()
                .author("Isaac Asimov")
                .category(AcquisitionCategory.BOOK)
                .libraryId(3L)
                .status(Status.ACTIVE)
                .requestedByUserId(1L)
                // title intentionally omitted (NOT NULL column)
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wantedBookRepository.saveAndFlush(invalid))
                .isInstanceOf(Exception.class);
    }
}
