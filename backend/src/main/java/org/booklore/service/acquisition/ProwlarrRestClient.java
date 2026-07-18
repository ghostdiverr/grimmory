package org.booklore.service.acquisition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.acquisition.ProwlarrReleaseDto;
import org.booklore.model.dto.settings.ProwlarrSettings;
import org.booklore.model.enums.AcquisitionCategory;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTTP client for Prowlarr's aggregated-indexer search/grab API.
 * <p>
 * Base URL and API key are admin-configurable at runtime via {@link ProwlarrSettings}, so
 * they are resolved per-request from {@link AppSettingService} rather than baked into a
 * static {@code RestClient} bean. The injected {@code RestClient} (from
 * {@code config/RestClientConfig}) is otherwise unconfigured, so every call here builds a
 * full absolute URI and sets the {@code X-Api-Key} header explicitly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProwlarrRestClient implements ProwlarrClient {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String SEARCH_PATH = "/api/v1/search";
    private static final String STATUS_PATH = "/api/v1/system/status";

    private final RestClient restClient;
    private final AppSettingService appSettingService;

    @Override
    public List<ProwlarrReleaseDto> search(String query, AcquisitionCategory category) {
        ProwlarrSettings settings = appSettingService.getAppSettings().getProwlarrSettings();
        if (!isConfigured(settings)) {
            log.warn("Prowlarr search skipped: Prowlarr is not configured or disabled");
            return List.of();
        }

        try {
            List<Integer> categoryIds = category == AcquisitionCategory.AUDIOBOOK
                    ? settings.getAudiobookCategories()
                    : settings.getBookCategories();

            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString(trimTrailingSlash(settings.getBaseUrl()) + SEARCH_PATH)
                    .queryParam("query", query)
                    .queryParam("type", "search");
            if (categoryIds != null && !categoryIds.isEmpty()) {
                uriBuilder.queryParam("categories", joinCategories(categoryIds));
            }
            URI uri = uriBuilder.build().toUri();

            ProwlarrSearchResultItem[] items = restClient.get()
                    .uri(uri)
                    .header(API_KEY_HEADER, settings.getApiKey())
                    .retrieve()
                    .body(ProwlarrSearchResultItem[].class);

            if (items == null) {
                return List.of();
            }

            return Arrays.stream(items)
                    .map(item -> toDto(item, category))
                    .toList();
        } catch (RestClientException e) {
            log.error("Prowlarr search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.error("Unexpected error during Prowlarr search for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void grab(ProwlarrReleaseDto release) {
        ProwlarrSettings settings = appSettingService.getAppSettings().getProwlarrSettings();
        if (!isConfigured(settings)) {
            throw new IllegalStateException("Prowlarr is not configured");
        }

        // ASSUMPTION - CONFIRM AGAINST A LIVE PROWLARR INSTANCE:
        // This mirrors Prowlarr's own UI "Download" grab action (POST /api/v1/search with a
        // { guid, indexerId } body), based on public Prowlarr API/OpenAPI-spec knowledge rather
        // than a verified call against a running instance. If grabs silently fail or Prowlarr
        // responds 4xx/5xx here, this payload shape is the first thing to re-check.
        Map<String, Object> body = Map.of(
                "guid", release.guid(),
                "indexerId", release.indexerId()
        );

        restClient.post()
                .uri(trimTrailingSlash(settings.getBaseUrl()) + SEARCH_PATH)
                .header(API_KEY_HEADER, settings.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public ProwlarrConnectionTestResult testConnection() {
        ProwlarrSettings settings = appSettingService.getAppSettings().getProwlarrSettings();
        if (settings == null || isBlank(settings.getBaseUrl()) || isBlank(settings.getApiKey())) {
            return new ProwlarrConnectionTestResult(false, "Prowlarr base URL and API key must be configured", null);
        }

        try {
            ProwlarrStatusResponse status = restClient.get()
                    .uri(trimTrailingSlash(settings.getBaseUrl()) + STATUS_PATH)
                    .header(API_KEY_HEADER, settings.getApiKey())
                    .retrieve()
                    .body(ProwlarrStatusResponse.class);

            if (status == null) {
                return new ProwlarrConnectionTestResult(false, "Empty response from Prowlarr", null);
            }
            return new ProwlarrConnectionTestResult(true, "Connected successfully", status.version());
        } catch (RestClientException e) {
            log.error("Prowlarr connection test failed: {}", e.getMessage());
            return new ProwlarrConnectionTestResult(false, "Connection failed: " + e.getMessage(), null);
        } catch (Exception e) {
            log.error("Unexpected error during Prowlarr connection test: {}", e.getMessage());
            return new ProwlarrConnectionTestResult(false, "Connection failed: " + e.getMessage(), null);
        }
    }

    private boolean isConfigured(ProwlarrSettings settings) {
        return settings != null && settings.isEnabled() && !isBlank(settings.getBaseUrl()) && !isBlank(settings.getApiKey());
    }

    private ProwlarrReleaseDto toDto(ProwlarrSearchResultItem item, AcquisitionCategory category) {
        return new ProwlarrReleaseDto(
                item.guid(),
                item.indexerId(),
                item.indexer(),
                item.title(),
                item.size(),
                item.seeders(),
                item.leechers(),
                item.publishDate(),
                item.downloadUrl(),
                item.protocol(),
                category
        );
    }

    private static String joinCategories(List<Integer> categoryIds) {
        return categoryIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // Package-private (not private) so ProwlarrRestClientTest can construct fixtures directly.
    record ProwlarrSearchResultItem(
            String guid,
            Long indexerId,
            String indexer,
            String title,
            Long size,
            Integer seeders,
            Integer leechers,
            String publishDate,
            String downloadUrl,
            String protocol
    ) {
    }

    record ProwlarrStatusResponse(String version) {
    }
}
