package org.booklore.service.appsettings;

import org.booklore.config.AppProperties;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.settings.AppSettingKey;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.ProwlarrSettings;
import org.booklore.model.entity.AppSettingEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.repository.AppSettingsRepository;
import org.booklore.service.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingServiceProwlarrTest {

    @Mock
    private AppProperties appProperties;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private AuditService auditService;
    @Mock
    private AppSettingsRepository appSettingsRepository;

    private SettingPersistenceHelper settingPersistenceHelper;
    private AppSettingService appSettingService;

    @BeforeEach
    void setUp() {
        settingPersistenceHelper = new SettingPersistenceHelper(appSettingsRepository, new ObjectMapper());
        appSettingService = new AppSettingService(appProperties, settingPersistenceHelper, authenticationService, auditService);
    }

    private BookLoreUser userWithPermissions(boolean admin, boolean manageMetadataConfig) {
        var permissions = new BookLoreUser.UserPermissions();
        permissions.setAdmin(admin);
        permissions.setCanManageMetadataConfig(manageMetadataConfig);
        return BookLoreUser.builder()
                .id(1L)
                .username("user")
                .permissions(permissions)
                .build();
    }

    @Test
    void updateSetting_roundTripsProwlarrSettingsAndAuditsUpdate() throws Exception {
        when(authenticationService.getAuthenticatedUser()).thenReturn(userWithPermissions(true, false));

        ProwlarrSettings settings = ProwlarrSettings.builder()
                .enabled(true)
                .baseUrl("http://prowlarr.local:9696")
                .apiKey("secret-key")
                .bookCategories(List.of(7000, 7020))
                .audiobookCategories(List.of(3030))
                .completedDownloadsFolder("/downloads")
                .bookSubfolder("books")
                .audiobookSubfolder("audiobooks")
                .build();

        appSettingService.updateSetting(AppSettingKey.PROWLARR_SETTINGS, settings);

        ArgumentCaptor<AppSettingEntity> settingCaptor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingsRepository).save(settingCaptor.capture());
        AppSettingEntity savedSetting = settingCaptor.getValue();
        assertThat(savedSetting.getName()).isEqualTo(AppSettingKey.PROWLARR_SETTINGS.toString());

        verify(auditService).log(AuditAction.PROWLARR_SETTINGS_UPDATED, "Updated setting: " + AppSettingKey.PROWLARR_SETTINGS);

        // Round-trip: feed the persisted value back and confirm getAppSettings() deserializes it.
        when(appProperties.getRemoteAuth()).thenReturn(new AppProperties.RemoteAuth());
        when(appSettingsRepository.findAll()).thenReturn(List.of(savedSetting));
        AppSettings appSettings = appSettingService.getAppSettings();

        ProwlarrSettings roundTripped = appSettings.getProwlarrSettings();
        assertThat(roundTripped.isEnabled()).isTrue();
        assertThat(roundTripped.getBaseUrl()).isEqualTo("http://prowlarr.local:9696");
        assertThat(roundTripped.getApiKey()).isEqualTo("secret-key");
        assertThat(roundTripped.getBookCategories()).containsExactly(7000, 7020);
        assertThat(roundTripped.getAudiobookCategories()).containsExactly(3030);
        assertThat(roundTripped.getCompletedDownloadsFolder()).isEqualTo("/downloads");
        assertThat(roundTripped.getBookSubfolder()).isEqualTo("books");
        assertThat(roundTripped.getAudiobookSubfolder()).isEqualTo("audiobooks");
    }

    @Test
    void updateSetting_deniesUserWithoutAdminOrMetadataConfigPermission() {
        when(authenticationService.getAuthenticatedUser()).thenReturn(userWithPermissions(false, false));

        assertThatThrownBy(() -> appSettingService.updateSetting(AppSettingKey.PROWLARR_SETTINGS, ProwlarrSettings.builder().build()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void updateSetting_allowsUserWithOnlyManageMetadataConfigPermission() throws Exception {
        when(authenticationService.getAuthenticatedUser()).thenReturn(userWithPermissions(false, true));

        appSettingService.updateSetting(AppSettingKey.PROWLARR_SETTINGS, ProwlarrSettings.builder().enabled(true).build());

        verify(appSettingsRepository).save(org.mockito.ArgumentMatchers.any());
    }
}
