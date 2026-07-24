package org.booklore.service.acquisition;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.mapper.WantedBookMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.acquisition.AcquisitionJobDto;
import org.booklore.model.dto.acquisition.CreateWantedBookRequest;
import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.dto.acquisition.WantedBookDto;
import org.booklore.model.entity.AcquisitionJobEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.WantedBookEntity;
import org.booklore.model.entity.WantedBookEntity.Status;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.AcquisitionJobRepository;
import org.booklore.repository.UserRepository;
import org.booklore.repository.WantedBookRepository;
import org.booklore.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WantedBookServiceTest {

    @Mock
    private WantedBookRepository wantedBookRepository;
    @Mock
    private WantedBookMapper wantedBookMapper;
    @Mock
    private AcquisitionJobRepository acquisitionJobRepository;
    @Mock
    private AcquisitionService acquisitionService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private UserRepository userRepository;

    private WantedBookService service;

    private static final ProwlarrReleaseDto GOOD_MATCH = new ProwlarrReleaseDto(
            "guid-good", 7L, "SomeIndexer", "Foundation", 123456L, 10, 2,
            "2024-01-01", "http://download/1", "torrent", AcquisitionCategory.BOOK
    );

    private static final ProwlarrReleaseDto POOR_MATCH = new ProwlarrReleaseDto(
            "guid-poor", 8L, "OtherIndexer", "Something Completely Different", 1000L, 20, 1,
            "2024-01-01", "http://download/2", "torrent", AcquisitionCategory.BOOK
    );

    @BeforeEach
    void setUp() {
        service = new WantedBookService(wantedBookRepository, wantedBookMapper, acquisitionJobRepository,
                acquisitionService, notificationService, authenticationService, userRepository);

        lenient().when(wantedBookRepository.save(any(WantedBookEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(wantedBookMapper.toDto(any(WantedBookEntity.class)))
                .thenAnswer(invocation -> {
                    WantedBookEntity entity = invocation.getArgument(0);
                    WantedBookDto dto = new WantedBookDto();
                    dto.setId(entity.getId());
                    dto.setStatus(entity.getStatus());
                    return dto;
                });
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());
    }

    private WantedBookEntity activeEntry() {
        return WantedBookEntity.builder()
                .id(1L)
                .title("Foundation")
                .author("Isaac Asimov")
                .category(AcquisitionCategory.BOOK)
                .libraryId(3L)
                .status(Status.ACTIVE)
                .requestedByUserId(42L)
                .build();
    }

    // ---- create() ----

    @Test
    void create_savesActiveEntryOwnedByCurrentUser() {
        BookLoreUser user = BookLoreUser.builder().id(42L).username("alice").build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);

        CreateWantedBookRequest request = new CreateWantedBookRequest("Foundation", "Isaac Asimov", null, AcquisitionCategory.BOOK, 3L);

        service.create(request);

        ArgumentCaptor<WantedBookEntity> captor = ArgumentCaptor.forClass(WantedBookEntity.class);
        verify(wantedBookRepository).save(captor.capture());
        WantedBookEntity saved = captor.getValue();

        assertThat(saved.getTitle()).isEqualTo("Foundation");
        assertThat(saved.getAuthor()).isEqualTo("Isaac Asimov");
        assertThat(saved.getCategory()).isEqualTo(AcquisitionCategory.BOOK);
        assertThat(saved.getLibraryId()).isEqualTo(3L);
        assertThat(saved.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(saved.getRequestedByUserId()).isEqualTo(42L);
    }

    // ---- pause() / resume() ----

    @Test
    void pause_flipsStatusToPaused() {
        WantedBookEntity entry = activeEntry();
        when(wantedBookRepository.findById(1L)).thenReturn(Optional.of(entry));

        service.pause(1L);

        assertThat(entry.getStatus()).isEqualTo(Status.PAUSED);
    }

    @Test
    void resume_flipsStatusToActive() {
        WantedBookEntity entry = activeEntry();
        entry.setStatus(Status.PAUSED);
        when(wantedBookRepository.findById(1L)).thenReturn(Optional.of(entry));

        service.resume(1L);

        assertThat(entry.getStatus()).isEqualTo(Status.ACTIVE);
    }

    // ---- delete() ----

    @Test
    void delete_removesEntry() {
        WantedBookEntity entry = activeEntry();
        when(wantedBookRepository.findById(1L)).thenReturn(Optional.of(entry));

        service.delete(1L);

        verify(wantedBookRepository).delete(entry);
    }

    // ---- runScanCycle() reconciliation ----

    @Test
    void runScanCycle_completedJob_marksEntryFulfilled() {
        WantedBookEntity entry = activeEntry();
        entry.setStatus(Status.GRABBED);
        entry.setAcquisitionJobId(99L);
        when(wantedBookRepository.findAllByStatus(Status.GRABBED)).thenReturn(List.of(entry));
        when(wantedBookRepository.findAllByStatus(Status.ACTIVE)).thenReturn(List.of());

        AcquisitionJobEntity job = AcquisitionJobEntity.builder().id(99L).status(AcquisitionJobEntity.Status.COMPLETED).build();
        when(acquisitionJobRepository.findById(99L)).thenReturn(Optional.of(job));

        service.runScanCycle();

        assertThat(entry.getStatus()).isEqualTo(Status.FULFILLED);
    }

    @Test
    void runScanCycle_failedJob_resetsEntryToActiveAndExcludesGuid() {
        WantedBookEntity entry = activeEntry();
        entry.setStatus(Status.GRABBED);
        entry.setAcquisitionJobId(99L);
        when(wantedBookRepository.findAllByStatus(Status.GRABBED)).thenReturn(List.of(entry));
        when(wantedBookRepository.findAllByStatus(Status.ACTIVE)).thenReturn(List.of());

        AcquisitionJobEntity job = AcquisitionJobEntity.builder()
                .id(99L).status(AcquisitionJobEntity.Status.FAILED).releaseGuid("guid-bad").build();
        when(acquisitionJobRepository.findById(99L)).thenReturn(Optional.of(job));

        service.runScanCycle();

        assertThat(entry.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(entry.getAcquisitionJobId()).isNull();
        assertThat(entry.getExcludedGuids()).contains("guid-bad");
    }

    @Test
    void runScanCycle_stillInFlightJob_leavesEntryUntouched() {
        WantedBookEntity entry = activeEntry();
        entry.setStatus(Status.GRABBED);
        entry.setAcquisitionJobId(99L);
        when(wantedBookRepository.findAllByStatus(Status.GRABBED)).thenReturn(List.of(entry));
        when(wantedBookRepository.findAllByStatus(Status.ACTIVE)).thenReturn(List.of());

        AcquisitionJobEntity job = AcquisitionJobEntity.builder().id(99L).status(AcquisitionJobEntity.Status.WAITING_FOR_FILE).build();
        when(acquisitionJobRepository.findById(99L)).thenReturn(Optional.of(job));

        service.runScanCycle();

        assertThat(entry.getStatus()).isEqualTo(Status.GRABBED);
        verify(wantedBookRepository, never()).save(entry);
    }

    // ---- runScanCycle() search + grab ----

    @Test
    void runScanCycle_matchAboveThreshold_grabsHighestSeededCandidate() {
        WantedBookEntity entry = activeEntry();
        when(wantedBookRepository.findAllByStatus(Status.GRABBED)).thenReturn(List.of());
        when(wantedBookRepository.findAllByStatus(Status.ACTIVE)).thenReturn(List.of(entry));

        when(acquisitionService.search(anyString(), any(AcquisitionCategory.class)))
                .thenReturn(List.of(GOOD_MATCH, POOR_MATCH));

        AcquisitionJobDto jobDto = new AcquisitionJobDto();
        jobDto.setId(55L);
        when(acquisitionService.grab(any(), any())).thenReturn(jobDto);

        service.runScanCycle();

        verify(acquisitionService).grab(argThat(req -> req.release().guid().equals("guid-good")), eq(42L));
        assertThat(entry.getStatus()).isEqualTo(Status.GRABBED);
        assertThat(entry.getAcquisitionJobId()).isEqualTo(55L);
        assertThat(entry.getLastSearchedAt()).isNotNull();
        assertThat(entry.getLastResultCount()).isEqualTo(2);
    }

    @Test
    void runScanCycle_noMatchAboveThreshold_staysActiveWithoutGrabbing() {
        WantedBookEntity entry = activeEntry();
        when(wantedBookRepository.findAllByStatus(Status.GRABBED)).thenReturn(List.of());
        when(wantedBookRepository.findAllByStatus(Status.ACTIVE)).thenReturn(List.of(entry));

        when(acquisitionService.search(anyString(), any(AcquisitionCategory.class)))
                .thenReturn(List.of(POOR_MATCH));

        service.runScanCycle();

        verify(acquisitionService, never()).grab(any(), any());
        assertThat(entry.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(entry.getLastSearchedAt()).isNotNull();
        assertThat(entry.getLastResultCount()).isEqualTo(1);
    }

    @Test
    void runScanCycle_excludesPreviouslyTriedGuids() {
        WantedBookEntity entry = activeEntry();
        entry.setExcludedGuids("guid-good");
        when(wantedBookRepository.findAllByStatus(Status.GRABBED)).thenReturn(List.of());
        when(wantedBookRepository.findAllByStatus(Status.ACTIVE)).thenReturn(List.of(entry));

        when(acquisitionService.search(anyString(), any(AcquisitionCategory.class)))
                .thenReturn(List.of(GOOD_MATCH, POOR_MATCH));

        service.runScanCycle();

        verify(acquisitionService, never()).grab(any(), any());
        assertThat(entry.getStatus()).isEqualTo(Status.ACTIVE);
    }
}
