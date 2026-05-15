package org.booklore.service.metadata.parser;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.BabelioCredentialsException;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.babelio.BabelioBookDetails;
import org.booklore.service.metadata.parser.babelio.BabelioSearchResult;
import org.booklore.util.BookUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class BabelioBookParser implements BookParser, DetailedMetadataProvider {

    private static final String API_URL = "https://www.babelio.com/api/appel.php";
    private static final String USER_AGENT = "2.0.138.2";
    private static final String BOUNDARY = "GrimmoryBoundary";
    private static final String CRLF = "\r\n";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        String babelioId = searchBabelioId(book, request);
        if (babelioId == null) return null;
        return fetchAndBuildMetadata(babelioId);
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        String babelioId = searchBabelioId(book, request);
        if (babelioId == null) return Collections.emptyList();
        BookMetadata metadata = fetchAndBuildMetadata(babelioId);
        return metadata != null ? List.of(metadata) : Collections.emptyList();
    }

    @Override
    public BookMetadata fetchDetailedMetadata(String babelioId) {
        return fetchAndBuildMetadata(babelioId);
    }

    private String searchBabelioId(Book book, FetchMetadataRequest request) {
        String term = buildSearchTerm(book, request);
        if (term == null || term.isBlank()) {
            log.warn("Babelio: no search term available");
            return null;
        }
        log.info("Babelio: searching for term={}", term);
        try {
            MetadataProviderSettings.Babelio settings = getSettings();
            String json = post(settings, Map.of("action", "suggesteur_recherche", "term", term));
            BabelioSearchResult result = objectMapper.readValue(json, BabelioSearchResult.class);
            checkCredentials(result.getSuccess(), result.getReason());
            if (result.getResults() == null || result.getResults().isEmpty()) {
                log.info("Babelio: no results for term={}", term);
                return null;
            }
            String id = result.getResults().getFirst().getIdOeuvre();
            log.info("Babelio: found id_oeuvre={} for term={}", id, term);
            return id;
        } catch (BabelioCredentialsException e) {
            throw e;
        } catch (Exception e) {
            log.error("Babelio: search failed for term={}", term, e);
            return null;
        }
    }

    private BookMetadata fetchAndBuildMetadata(String babelioId) {
        log.info("Babelio: fetching details for id_oeuvre={}", babelioId);
        try {
            MetadataProviderSettings.Babelio settings = getSettings();
            String json = post(settings, Map.of("action", "book_all", "book_id", babelioId));
            BabelioBookDetails details = objectMapper.readValue(json, BabelioBookDetails.class);
            checkCredentials(details.getSuccess(), details.getReason());
            if (details.getBookAll() == null
                    || details.getBookAll().getBookInfoGlobal() == null
                    || details.getBookAll().getBookInfoGlobal().getBookInfo() == null) {
                log.warn("Babelio: empty book_all payload for id={}", babelioId);
                return null;
            }
            return mapToBookMetadata(details);
        } catch (BabelioCredentialsException e) {
            throw e;
        } catch (Exception e) {
            log.error("Babelio: detail fetch failed for id={}", babelioId, e);
            return null;
        }
    }

    private BookMetadata mapToBookMetadata(BabelioBookDetails details) {
        BabelioBookDetails.BookInfo info = details.getBookAll().getBookInfoGlobal().getBookInfo();

        List<String> authors = Optional.ofNullable(info.getAuthorList())
                .orElse(Collections.emptyList())
                .stream()
                .map(a -> (a.getFirstName() + " " + a.getLastName()).trim())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        Set<String> categories = Optional.ofNullable(details.getBookAll().getTagsOnBook())
                .map(BabelioBookDetails.TagsOnBook::getTags)
                .orElse(Collections.emptyList())
                .stream()
                .map(BabelioBookDetails.Tag::getLibelle)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return BookMetadata.builder()
                .provider(MetadataProvider.Babelio)
                .title(info.getBookTitle())
                .authors(authors)
                .categories(categories)
                .description(info.getSummary())
                .isbn10(ParserUtils.cleanIsbn(info.getIsbn10()))
                .isbn13(ParserUtils.cleanIsbn(info.getEan13()))
                .publisher(info.getPublisherName())
                .publishedDate(parseDate(info.getPublishingDate()))
                .pageCount(parseInteger(info.getNbPages()))
                .rating(parseDouble(info.getAverageRating()))
                .thumbnailUrl(info.getCoverUrl())
                .seriesName(info.getSerieName())
                .seriesNumber(parseFloat(info.getTome()))
                .build();
    }

    private String post(MetadataProviderSettings.Babelio settings, Map<String, String> extraFields)
            throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("user_id", settings.getUserId());
        fields.put("session_id", settings.getSessionId());
        fields.put("timestamp", String.valueOf(System.currentTimeMillis()));
        fields.putAll(extraFields);

        String body = buildMultipartBody(fields);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Babelio API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String buildMultipartBody(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            sb.append("--").append(BOUNDARY).append(CRLF)
              .append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"").append(CRLF)
              .append(CRLF)
              .append(entry.getValue()).append(CRLF);
        }
        sb.append("--").append(BOUNDARY).append("--").append(CRLF);
        return sb.toString();
    }

    private MetadataProviderSettings.Babelio getSettings() {
        MetadataProviderSettings.Babelio settings = appSettingService.getAppSettings()
                .getMetadataProviderSettings().getBabelio();
        if (settings == null
                || settings.getUserId() == null || settings.getUserId().isBlank()
                || settings.getSessionId() == null || settings.getSessionId().isBlank()) {
            throw new BabelioCredentialsException(
                    "Session Babelio expirée, veuillez mettre à jour vos paramètres");
        }
        return settings;
    }

    private void checkCredentials(int success, String reason) {
        if (success == 0 && reason != null && reason.contains("session_id")) {
            throw new BabelioCredentialsException(
                    "Session Babelio expirée, veuillez mettre à jour vos paramètres");
        }
    }

    private String buildSearchTerm(Book book, FetchMetadataRequest request) {
        String isbn = ParserUtils.cleanIsbn(request.getIsbn());
        if (isbn != null && !isbn.isBlank()) return isbn;
        if (request.getTitle() != null && !request.getTitle().isBlank()) return request.getTitle();
        if (book.getPrimaryFile() != null && book.getPrimaryFile().getFileName() != null) {
            return BookUtils.cleanAndTruncateSearchTerm(
                    BookUtils.cleanFileName(book.getPrimaryFile().getFileName()));
        }
        return null;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 10) return null;
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (Exception e) {
            log.debug("Babelio: cannot parse date '{}'", raw);
            return null;
        }
    }

    private Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Float parseFloat(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
