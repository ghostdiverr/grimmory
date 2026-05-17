package org.booklore.service.metadata.parser;

import org.booklore.exception.BabelioCredentialsException;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BabelioBookParserTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private HttpClient httpClient;

    @Mock
    private AppSettingService appSettingService;

    @InjectMocks
    private BabelioBookParser parser;

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int statusCode, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }

    private String readFixture(String name) throws IOException {
        String path = "babelio/" + name;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Fixture not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private AppSettings enabledSettings() {
        MetadataProviderSettings.Babelio babelio = new MetadataProviderSettings.Babelio();
        babelio.setEnabled(true);
        babelio.setUserLogin("user@example.com");
        babelio.setPassword("secret");
        MetadataProviderSettings settings = new MetadataProviderSettings();
        settings.setBabelio(babelio);
        return AppSettings.builder().metadataProviderSettings(settings).build();
    }

    private FetchMetadataRequest isbnRequest(String isbn) {
        return FetchMetadataRequest.builder().isbn(isbn).build();
    }

    private FetchMetadataRequest titleRequest(String title) {
        return FetchMetadataRequest.builder().title(title).build();
    }

    private Book emptyBook() {
        return Book.builder().build();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void fetchTopMetadata_noSearchTerm_returnsNull() {
        BookMetadata result = parser.fetchTopMetadata(emptyBook(), FetchMetadataRequest.builder().build());
        assertNull(result);
        verifyNoInteractions(httpClient, appSettingService);
    }

    @Test
    void fetchMetadata_missingCredentials_throwsException() {
        MetadataProviderSettings.Babelio babelio = new MetadataProviderSettings.Babelio();
        babelio.setEnabled(true);
        MetadataProviderSettings settings = new MetadataProviderSettings();
        settings.setBabelio(babelio);
        when(appSettingService.getAppSettings()).thenReturn(
                AppSettings.builder().metadataProviderSettings(settings).build());

        assertThrows(BabelioCredentialsException.class,
                () -> parser.fetchMetadata(emptyBook(), isbnRequest("9782070612758")));
    }

    @Test
    void fetchMetadata_byIsbn_returnsMappedMetadata() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("search-book-isbn.json"));
        HttpResponse<String> r3 = mockResponse(200, readFixture("book-all.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2).thenReturn(r3);

        List<BookMetadata> results = parser.fetchMetadata(emptyBook(), isbnRequest("9782070612758"));

        assertEquals(1, results.size());
        BookMetadata m = results.getFirst();
        assertEquals("Le Petit Prince", m.getTitle());
        assertEquals(1, m.getAuthors().size());
        assertEquals("Antoine de Saint-Exupéry", m.getAuthors().getFirst());
        assertEquals("9782070612758", m.getIsbn13());
        assertNotNull(m.getThumbnailUrl());
        assertTrue(m.getThumbnailUrl().startsWith("https://www.babelio.com"));
    }

    @Test
    void fetchMetadata_byIsbn_noResults_returnsEmpty() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("search-book-empty.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2);

        List<BookMetadata> results = parser.fetchMetadata(emptyBook(), isbnRequest("9780000000000"));

        assertTrue(results.isEmpty());
    }

    @Test
    void fetchMetadata_byTerm_returnsMappedMetadata() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("suggesteur-results.json"));
        HttpResponse<String> r3 = mockResponse(200, readFixture("book-all.json"));
        HttpResponse<String> r4 = mockResponse(200, readFixture("book-all.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2).thenReturn(r3).thenReturn(r4);

        List<BookMetadata> results = parser.fetchMetadata(emptyBook(), titleRequest("Le Petit Prince"));

        assertEquals(2, results.size());
        assertEquals("Le Petit Prince", results.getFirst().getTitle());
    }

    @Test
    void fetchMetadata_byTerm_noResults_returnsEmpty() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("suggesteur-empty.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2);

        List<BookMetadata> results = parser.fetchMetadata(emptyBook(), titleRequest("nonexistent"));

        assertTrue(results.isEmpty());
    }

    @Test
    void fetchTopMetadata_byIsbn_returnsFirstResult() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("search-book-isbn.json"));
        HttpResponse<String> r3 = mockResponse(200, readFixture("book-all.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2).thenReturn(r3);

        BookMetadata result = parser.fetchTopMetadata(emptyBook(), isbnRequest("9782070612758"));

        assertNotNull(result);
        assertEquals("Le Petit Prince", result.getTitle());
    }

    @Test
    void fetchDetailedMetadata_returnsMappedMetadata() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("book-all.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2);

        BookMetadata result = parser.fetchDetailedMetadata("67890");

        assertNotNull(result);
        assertEquals("Le Petit Prince", result.getTitle());
        assertEquals("67890", result.getBabelioId());
        assertFalse(result.getCategories().isEmpty());
        assertTrue(result.getCategories().contains("Classique"));
    }

    @Test
    void post_authFailureCode4_retriesLogin() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("auth-failure.json"));
        HttpResponse<String> r3 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r4 = mockResponse(200, readFixture("book-all.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2).thenReturn(r3).thenReturn(r4);

        BookMetadata result = parser.fetchDetailedMetadata("67890");

        assertNotNull(result);
        assertEquals("Le Petit Prince", result.getTitle());
        verify(httpClient, times(4)).send(any(HttpRequest.class), any());
    }

    @Test
    void mapToBookMetadata_seriesAndRatingMapped() throws IOException, InterruptedException {
        when(appSettingService.getAppSettings()).thenReturn(enabledSettings());
        HttpResponse<String> r1 = mockResponse(200, readFixture("login-success.json"));
        HttpResponse<String> r2 = mockResponse(200, readFixture("book-all.json"));
        when(httpClient.<String>send(any(HttpRequest.class), any()))
                .thenReturn(r1).thenReturn(r2);

        BookMetadata result = parser.fetchDetailedMetadata("67890");

        assertNotNull(result);
        assertEquals("Le Petit Prince", result.getSeriesName());
        assertEquals(1.0f, result.getSeriesNumber());
        assertEquals(4.32, result.getRating(), 0.001);
        assertEquals(96, result.getPageCount());
        assertEquals("Gallimard", result.getPublisher());
    }
}
