package org.booklore.service.acquisition;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.mapper.AcquisitionJobMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.acquisition.GrabRequest;
import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcquisitionServiceTest {

    @Mock
    private ProwlarrClient prowlarrClient;
    @Mock
    private AcquisitionJobRepository acquisitionJobRepository;
    @Mock
    private AcquisitionJobMapper acquisitionJobMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuthenticationService authenticationService;

    private AcquisitionService acquisitionService;

    private static final ProwlarrReleaseDto RELEASE = new ProwlarrReleaseDto(
            "guid-1", 7L, "SomeIndexer", "Foundation", 123456L, 10, 2,
            "2024-01-01", "http://download/1", "torrent", AcquisitionCategory.BOOK
    );

    private AcquisitionService service() {
        return new AcquisitionService(prowlarrClient, acquisitionJobRepository, acquisitionJobMapper, notificationService, authenticationService);
    }

    // ---- search() ----

    @Test
    void search_isPurePassthrough_withNoRepositoryInteraction() {
        acquisitionService = service();
        when(prowlarrClient.search("Foundation", AcquisitionCategory.BOOK)).thenReturn(List.of(RELEASE));

        List<ProwlarrReleaseDto> results = acquisitionService.search("Foundation", AcquisitionCategory.BOOK);

        assertThat(results).containsExactly(RELEASE);
        verifyNoInteractions(acquisitionJobRepository, acquisitionJobMapper, notificationService);
    }

    // ---- grab() ----

    @Test
    void grab_persistsJobWithCorrectFieldsAndEmitsWebsocketUpdate() {
        acquisitionService = service();

        BookLoreUser user = BookLoreUser.builder().id(42L).username("alice").build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        AcquisitionJobEntity savedEntity = AcquisitionJobEntity.builder().id(99L).build();
        when(acquisitionJobRepository.save(any(AcquisitionJobEntity.class))).thenReturn(savedEntity);

        AcquisitionJobDto dto = new AcquisitionJobDto();
        dto.setId(99L);
        when(acquisitionJobMapper.toDto(savedEntity)).thenReturn(dto);

        GrabRequest request = new GrabRequest(RELEASE, "Foundation", 5L, 6L);

        AcquisitionJobDto result = acquisitionService.grab(request);

        verify(prowlarrClient).grab(RELEASE);

        ArgumentCaptor<AcquisitionJobEntity> jobCaptor = ArgumentCaptor.forClass(AcquisitionJobEntity.class);
        verify(acquisitionJobRepository, times(2)).save(jobCaptor.capture());
        List<AcquisitionJobEntity> persistedJobs = jobCaptor.getAllValues();
        AcquisitionJobEntity firstSave = persistedJobs.get(0);
        AcquisitionJobEntity secondSave = persistedJobs.get(1);

        assertThat(firstSave.getBookId()).isEqualTo(5L);
        assertThat(firstSave.getLibraryId()).isEqualTo(6L);
        assertThat(firstSave.getQuery()).isEqualTo("Foundation");
        assertThat(firstSave.getCategory()).isEqualTo(AcquisitionCategory.BOOK);
        assertThat(firstSave.getReleaseTitle()).isEqualTo("Foundation");
        assertThat(firstSave.getReleaseGuid()).isEqualTo("guid-1");
        assertThat(firstSave.getIndexerId()).isEqualTo(7L);
        assertThat(firstSave.getIndexerName()).isEqualTo("SomeIndexer");
        assertThat(firstSave.getProtocol()).isEqualTo("torrent");
        assertThat(firstSave.getSizeBytes()).isEqualTo(123456L);
        assertThat(firstSave.getStatus()).isEqualTo(AcquisitionJobEntity.Status.GRABBED);
        assertThat(firstSave.getRequestedByUserId()).isEqualTo(42L);
        assertThat(firstSave.getGrabbedAt()).isNotNull();

        // Second save transitions the (mock-returned) persisted entity to WAITING_FOR_FILE,
        // documenting the "now watching for the file" moment separately from GRABBED.
        assertThat(secondSave.getStatus()).isEqualTo(AcquisitionJobEntity.Status.WAITING_FOR_FILE);

        verify(notificationService, times(2)).sendMessage(Topic.ACQUISITION_JOB_UPDATE, dto);
        assertThat(result).isSameAs(dto);
    }

    @Test
    void grab_withExplicitUserId_attributesJobToThatUserWithoutTouchingSecurityContext() {
        acquisitionService = service();

        AcquisitionJobEntity savedEntity = AcquisitionJobEntity.builder().id(99L).build();
        when(acquisitionJobRepository.save(any(AcquisitionJobEntity.class))).thenReturn(savedEntity);

        AcquisitionJobDto dto = new AcquisitionJobDto();
        dto.setId(99L);
        when(acquisitionJobMapper.toDto(savedEntity)).thenReturn(dto);

        GrabRequest request = new GrabRequest(RELEASE, "Foundation", null, 6L);

        acquisitionService.grab(request, 77L);

        ArgumentCaptor<AcquisitionJobEntity> jobCaptor = ArgumentCaptor.forClass(AcquisitionJobEntity.class);
        verify(acquisitionJobRepository, times(2)).save(jobCaptor.capture());
        assertThat(jobCaptor.getAllValues().get(0).getRequestedByUserId()).isEqualTo(77L);
        verifyNoInteractions(authenticationService);
    }

    // ---- listJobs() ----

    @Test
    void listJobs_mapsAllPersistedJobsToDtos() {
        acquisitionService = service();

        AcquisitionJobEntity job1 = AcquisitionJobEntity.builder().id(1L).build();
        AcquisitionJobEntity job2 = AcquisitionJobEntity.builder().id(2L).build();
        when(acquisitionJobRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(job1, job2));

        AcquisitionJobDto dto1 = new AcquisitionJobDto();
        dto1.setId(1L);
        AcquisitionJobDto dto2 = new AcquisitionJobDto();
        dto2.setId(2L);
        when(acquisitionJobMapper.toDto(job1)).thenReturn(dto1);
        when(acquisitionJobMapper.toDto(job2)).thenReturn(dto2);

        List<AcquisitionJobDto> result = acquisitionService.listJobs();

        assertThat(result).containsExactly(dto1, dto2);
        verifyNoInteractions(notificationService);
    }

    // ---- cancelJob() ----

    @Test
    void cancelJob_flipsStatusToFailedAndEmitsUpdate() {
        acquisitionService = service();

        AcquisitionJobEntity existing = AcquisitionJobEntity.builder()
                .id(7L)
                .status(AcquisitionJobEntity.Status.GRABBED)
                .build();
        when(acquisitionJobRepository.findById(7L)).thenReturn(java.util.Optional.of(existing));
        when(acquisitionJobRepository.save(any(AcquisitionJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AcquisitionJobDto dto = new AcquisitionJobDto();
        dto.setId(7L);
        dto.setStatus(AcquisitionJobEntity.Status.FAILED);
        when(acquisitionJobMapper.toDto(any(AcquisitionJobEntity.class))).thenReturn(dto);

        AcquisitionJobDto result = acquisitionService.cancelJob(7L);

        ArgumentCaptor<AcquisitionJobEntity> jobCaptor = ArgumentCaptor.forClass(AcquisitionJobEntity.class);
        verify(acquisitionJobRepository, times(1)).save(jobCaptor.capture());
        AcquisitionJobEntity saved = jobCaptor.getValue();

        assertThat(saved.getStatus()).isEqualTo(AcquisitionJobEntity.Status.FAILED);
        assertThat(saved.getErrorMessage()).isNotBlank();
        assertThat(saved.getCompletedAt()).isNotNull();

        verify(notificationService).sendMessage(Topic.ACQUISITION_JOB_UPDATE, dto);
        assertThat(result).isSameAs(dto);
        verify(prowlarrClient, never()).grab(any());
    }
}
