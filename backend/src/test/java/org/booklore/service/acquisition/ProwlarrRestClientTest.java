package org.booklore.service.acquisition;

import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.ProwlarrSettings;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProwlarrRestClientTest {

    private static final String BASE_URL = "http://prowlarr.local:9696";
    private static final String API_KEY = "test-api-key";

    @Mock
    private RestClient restClient;
    @Mock
    private AppSettingService appSettingService;

    @Mock
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock
    private RestClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ProwlarrRestClient prowlarrRestClient;

    @BeforeEach
    void setUp() {
        prowlarrRestClient = new ProwlarrRestClient(restClient, appSettingService);
    }

    private void stubSettings(ProwlarrSettings settings) {
        AppSettings appSettings = AppSettings.builder().prowlarrSettings(settings).build();
        when(appSettingService.getAppSettings()).thenReturn(appSettings);
    }

    private ProwlarrSettings enabledSettings() {
        return ProwlarrSettings.builder()
                .enabled(true)
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .bookCategories(List.of(7000, 7020))
                .audiobookCategories(List.of(3030))
                .build();
    }

    // ---- search() ----

    @Test
    void search_success_parsesReleasesAndSendsApiKeyHeader() {
        stubSettings(enabledSettings());

        ProwlarrRestClient.ProwlarrSearchResultItem item = new ProwlarrRestClient.ProwlarrSearchResultItem(
                "guid-1", 5L, "MyIndexer", "Foundation", 123456L, 10, 2, "2024-01-01", "http://download/1", "torrent"
        );

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ProwlarrRestClient.ProwlarrSearchResultItem[].class))
                .thenReturn(new ProwlarrRestClient.ProwlarrSearchResultItem[]{item});

        List<ProwlarrReleaseDto> results = prowlarrRestClient.search("Foundation", AcquisitionCategory.BOOK);

        assertThat(results).hasSize(1);
        ProwlarrReleaseDto dto = results.getFirst();
        assertThat(dto.guid()).isEqualTo("guid-1");
        assertThat(dto.indexerId()).isEqualTo(5L);
        assertThat(dto.indexerName()).isEqualTo("MyIndexer");
        assertThat(dto.title()).isEqualTo("Foundation");
        assertThat(dto.size()).isEqualTo(123456L);
        assertThat(dto.seeders()).isEqualTo(10);
        assertThat(dto.leechers()).isEqualTo(2);
        assertThat(dto.publishDate()).isEqualTo("2024-01-01");
        assertThat(dto.downloadUrl()).isEqualTo("http://download/1");
        assertThat(dto.protocol()).isEqualTo("torrent");
        assertThat(dto.category()).isEqualTo(AcquisitionCategory.BOOK);

        verify(requestHeadersSpec).header("X-Api-Key", API_KEY);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(requestHeadersUriSpec).uri(uriCaptor.capture());
        URI capturedUri = uriCaptor.getValue();
        assertThat(capturedUri.toString()).startsWith(BASE_URL + "/api/v1/search");
        assertThat(capturedUri.getQuery()).contains("query=Foundation");
        assertThat(capturedUri.getQuery()).contains("type=search");
        assertThat(capturedUri.getQuery()).contains("categories=7000");
        assertThat(capturedUri.getQuery()).contains("categories=7020");
        assertThat(capturedUri.getQuery()).doesNotContain("categories=7000,7020");
    }

    @Test
    void search_httpError_returnsEmptyListNotException() {
        stubSettings(enabledSettings());

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenThrow(new RestClientException("connection refused"));

        List<ProwlarrReleaseDto> results = prowlarrRestClient.search("Foundation", AcquisitionCategory.BOOK);

        assertThat(results).isEmpty();
    }

    @Test
    void search_malformedResponse_returnsEmptyListNotException() {
        stubSettings(enabledSettings());

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ProwlarrRestClient.ProwlarrSearchResultItem[].class)).thenReturn(null);

        List<ProwlarrReleaseDto> results = prowlarrRestClient.search("Foundation", AcquisitionCategory.BOOK);

        assertThat(results).isEmpty();
    }

    @Test
    void search_disabledOrUnconfigured_returnsEmptyListWithoutCallingRestClient() {
        stubSettings(ProwlarrSettings.builder().enabled(false).build());

        List<ProwlarrReleaseDto> results = prowlarrRestClient.search("Foundation", AcquisitionCategory.BOOK);

        assertThat(results).isEmpty();
        verifyNoInteractions(restClient);
    }

    // ---- grab() ----

    @Test
    void grab_postsExpectedPathAndBodyWithApiKeyHeader() {
        stubSettings(enabledSettings());

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        ProwlarrReleaseDto release = new ProwlarrReleaseDto(
                "guid-42", 9L, "SomeIndexer", "Title", 1000L, 5, 1, "2024-01-01",
                "http://download/42", "torrent", AcquisitionCategory.BOOK
        );

        prowlarrRestClient.grab(release);

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestBodyUriSpec).uri(uriCaptor.capture());
        assertThat(uriCaptor.getValue()).isEqualTo(BASE_URL + "/api/v1/search");

        verify(requestBodySpec).header("X-Api-Key", API_KEY);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isEqualTo(Map.of("guid", "guid-42", "indexerId", 9L));
    }

    // ---- testConnection() ----

    @Test
    void testConnection_success_mapsVersion() {
        stubSettings(enabledSettings());

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(ProwlarrRestClient.ProwlarrStatusResponse.class))
                .thenReturn(new ProwlarrRestClient.ProwlarrStatusResponse("1.2.3.4"));

        ProwlarrConnectionTestResult result = prowlarrRestClient.testConnection();

        assertThat(result.success()).isTrue();
        assertThat(result.version()).isEqualTo("1.2.3.4");
    }

    @Test
    void testConnection_nonOkResponse_mapsToFailure() {
        stubSettings(enabledSettings());

        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenThrow(new RestClientException("401 Unauthorized"));

        ProwlarrConnectionTestResult result = prowlarrRestClient.testConnection();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("401 Unauthorized");
        assertThat(result.version()).isNull();
    }

    @Test
    void testConnection_unconfigured_returnsFailureWithoutCallingRestClient() {
        stubSettings(ProwlarrSettings.builder().enabled(false).build());

        ProwlarrConnectionTestResult result = prowlarrRestClient.testConnection();

        assertThat(result.success()).isFalse();
        verify(restClient, never()).get();
    }
}
