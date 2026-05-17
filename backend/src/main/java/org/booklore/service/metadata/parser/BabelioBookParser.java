package org.booklore.service.metadata.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.babelio.BabelioBookDetails;
import org.booklore.service.metadata.parser.babelio.BabelioEditionsResponse;
import org.booklore.service.metadata.parser.babelio.BabelioLoginResponse;
import org.booklore.service.metadata.parser.babelio.BabelioSearchBookResponse;
import org.booklore.service.metadata.parser.babelio.BabelioSearchResult;
import org.booklore.util.BookUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BabelioBookParser implements BookParser, DetailedMetadataProvider {

    private static final String API_URL = "https://www.babelio.com/api/appel.php";
    private static final String USER_AGENT = "2.0.138.2";
    private static final String BOUNDARY = "GrimmoryBoundary";
    private static final String CRLF = "\r\n";

    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String cachedToken;
    private volatile String cachedUserId;

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        String isbn = ParserUtils.cleanIsbn(request.getIsbn());
        if (isbn != null && !isbn.isBlank()) {
            List<BookMetadata> results = fetchByIsbn(isbn);
            return results.isEmpty() ? null : results.getFirst();
        }
        String babelioId = searchBabelioId(book, request);
        if (babelioId == null) return null;
        return fetchAndBuildMetadata(babelioId, null);
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        String isbn = ParserUtils.cleanIsbn(request.getIsbn());
        if (isbn != null && !isbn.isBlank()) {
            return fetchByIsbn(isbn);
        }
        return fetchByTerm(book, request);
    }

    private List<BookMetadata> fetchByIsbn(String isbn) {
        log.info("Babelio: ISBN lookup — action=search_book request={}", isbn);
        try {
            String json = post(Map.of("action", "search_book", "request", isbn, "page", "1"));
            if (json == null) return Collections.emptyList();
            BabelioSearchBookResponse resp = objectMapper.readValue(json, BabelioSearchBookResponse.class);
            if (resp.getBooks() == null || resp.getBooks().isEmpty()) {
                log.info("Babelio: ISBN lookup returned no results for isbn={}", isbn);
                return Collections.emptyList();
            }
            log.info("Babelio: ISBN lookup returned {} result(s) for isbn={}", resp.getBooks().size(), isbn);
            return resp.getBooks().stream()
                    .filter(b -> b.getBookId() != null)
                    .map(b -> {
                        log.info("Babelio: fetching details — action=book_all book_id={} id_edition={}", b.getBookId(), b.getIdEdition());
                        return fetchAndBuildMetadata(b.getBookId(), b.getIdEdition());
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.error("Babelio: ISBN lookup failed for isbn={}: {}", isbn, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<BookMetadata> fetchByTerm(Book book, FetchMetadataRequest request) {
        String term = buildSearchTerm(book, request);
        if (term == null || term.isBlank()) {
            log.warn("Babelio: no search term available (no ISBN, no title, no filename)");
            return Collections.emptyList();
        }
        log.info("Babelio: text search — action=suggesteur_recherche term=\"{}\"", term);
        try {
            String json = post(Map.of("action", "suggesteur_recherche", "term", term, "test_series", "1"));
            if (json == null) return Collections.emptyList();
            BabelioSearchResult result = objectMapper.readValue(json, BabelioSearchResult.class);
            if (result.getResults() == null || result.getResults().isEmpty()) {
                log.info("Babelio: text search returned no results for term=\"{}\"", term);
                return Collections.emptyList();
            }
            List<BabelioSearchResult.Result> books = result.getResults().stream()
                    .filter(r -> "livres".equals(r.getType()) && r.getIdOeuvre() != null)
                    .toList();
            log.info("Babelio: text search returned {} book result(s) for term=\"{}\"", books.size(), term);
            return books.stream()
                    .map(r -> {
                        log.info("Babelio: fetching details — action=book_all book_id={} id_edition=undefined", r.getIdOeuvre());
                        return fetchAndBuildMetadata(r.getIdOeuvre(), null);
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.error("Babelio: text search failed for term=\"{}\": {}", term, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public BookMetadata fetchDetailedMetadata(String babelioId) {
        log.info("Babelio: detail enrichment — action=book_all book_id={} id_edition=undefined", babelioId);
        return fetchAndBuildMetadata(babelioId, null);
    }

    private String searchBabelioId(Book book, FetchMetadataRequest request) {
        String term = buildSearchTerm(book, request);
        if (term == null || term.isBlank()) {
            log.warn("Babelio: no search term available (no ISBN, no title, no filename)");
            return null;
        }
        log.info("Babelio: top-metadata search — action=suggesteur_recherche term=\"{}\"", term);
        try {
            String json = post(Map.of("action", "suggesteur_recherche", "term", term));
            if (json == null) return null;
            BabelioSearchResult result = objectMapper.readValue(json, BabelioSearchResult.class);
            if (result.getResults() == null || result.getResults().isEmpty()) {
                log.info("Babelio: search returned no results for term=\"{}\"", term);
                return null;
            }
            String id = result.getResults().getFirst().getIdOeuvre();
            log.info("Babelio: using top result book_id={} for term=\"{}\"", id, term);
            return id;
        } catch (Exception e) {
            log.error("Babelio: search failed for term=\"{}\": {}", term, e.getMessage());
        }
        return null;
    }

    private BookMetadata fetchAndBuildMetadata(String babelioId, String idEdition) {
        String editionParam = (idEdition != null && !idEdition.isBlank()) ? idEdition : "undefined";
        try {
            String json = post(Map.of("action", "book_all", "book_id", babelioId,
                    "id_edition", editionParam, "no_cache", "1"));
            if (json == null) return null;
            BabelioBookDetails details = objectMapper.readValue(json, BabelioBookDetails.class);
            if (details.getBookAll() == null
                    || details.getBookAll().getBookInfoGlobal() == null
                    || details.getBookAll().getBookInfoGlobal().getBookInfo() == null) {
                log.warn("Babelio: book_all returned empty payload for book_id={} id_edition={}", babelioId, editionParam);
                return null;
            }
            String title = details.getBookAll().getBookInfoGlobal().getBookInfo().getBookTitle();
            log.info("Babelio: book_all OK — book_id={} id_edition={} title=\"{}\"", babelioId, editionParam, title);

            BabelioEditionsResponse.Edition editionOverride = null;
            if (idEdition != null && !idEdition.isBlank()) {
                editionOverride = fetchEditionOverride(babelioId, idEdition);
                if (editionOverride != null) {
                    log.info("Babelio: edition override applied — id_edition={}", idEdition);
                } else {
                    log.warn("Babelio: edition id={} not found in f_editions_livre for book_id={}", idEdition, babelioId);
                }
            }

            return mapToBookMetadata(babelioId, details, editionOverride);
        } catch (Exception e) {
            log.error("Babelio: book_all failed for book_id={} id_edition={}: {}", babelioId, editionParam, e.getMessage());
            return null;
        }
    }

    private BabelioEditionsResponse.Edition fetchEditionOverride(String bookId, String idEdition) {
        log.info("Babelio: fetching edition data — action=f_editions_livre id_oeuvre={} target_id_edition={}", bookId, idEdition);
        try {
            String json = post(Map.of("action", "f_editions_livre", "id_oeuvre", bookId, "page", "1"));
            if (json == null) return null;
            BabelioEditionsResponse resp = objectMapper.readValue(json, BabelioEditionsResponse.class);
            if (resp.getEditions() == null) return null;
            return resp.getEditions().stream()
                    .filter(e -> idEdition.equals(e.getId()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Babelio: f_editions_livre failed for id_oeuvre={}: {}", bookId, e.getMessage());
            return null;
        }
    }

    private BookMetadata mapToBookMetadata(String babelioId, BabelioBookDetails details, BabelioEditionsResponse.Edition edition) {
        BabelioBookDetails.BookInfo info = details.getBookAll().getBookInfoGlobal().getBookInfo();

        List<String> authors = Optional.ofNullable(info.getAuthorList())
                .orElse(Collections.emptyList())
                .stream()
                .map(a -> (a.getFirstName() + " " + a.getLastName()).trim())
                .filter(s -> !s.isBlank())
                .toList();

        Set<String> categories = Optional.ofNullable(details.getBookAll().getTagsOnBook())
                .map(BabelioBookDetails.TagsOnBook::getTags)
                .orElse(Collections.emptyList())
                .stream()
                .map(BabelioBookDetails.Tag::getLibelle)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        BabelioBookDetails.Serie serie = Optional.ofNullable(details.getBookAll().getSerie())
                .filter(l -> !l.isEmpty())
                .map(l -> l.getFirst())
                .orElse(null);

        String isbn10 = edition != null && edition.getIsbn() != null
                ? ParserUtils.cleanIsbn(edition.getIsbn())
                : ParserUtils.cleanIsbn(info.getIsbn10());
        String isbn13 = edition != null && edition.getEan13() != null
                ? ParserUtils.cleanIsbn(edition.getEan13())
                : ParserUtils.cleanIsbn(info.getEan13());
        String publisher = edition != null && edition.getNom() != null
                ? edition.getNom()
                : info.getPublisherName();
        LocalDate publishedDate = edition != null && edition.getDtPublication() != null
                ? parseDate(edition.getDtPublication())
                : parseDate(info.getPublishingDate());
        Integer pageCount = edition != null && edition.getNbPages() != null
                ? parseInteger(edition.getNbPages())
                : parseInteger(info.getNbPages());
        String thumbnailUrl = edition != null && edition.getCouverture() != null
                ? buildCoverUrl(edition.getCouverture())
                : buildCoverUrl(info.getCoverUrl());

        return BookMetadata.builder()
                .provider(MetadataProvider.Babelio)
                .babelioId(babelioId)
                .title(info.getBookTitle())
                .authors(authors)
                .categories(categories)
                .description(info.getSummary())
                .isbn10(isbn10)
                .isbn13(isbn13)
                .publisher(publisher)
                .publishedDate(publishedDate)
                .pageCount(pageCount)
                .rating(parseDouble(info.getAverageRating()))
                .thumbnailUrl(thumbnailUrl)
                .seriesName(serie != null ? serie.getNom() : null)
                .seriesNumber(serie != null ? parseFloat(serie.getTome()) : null)
                .build();
    }

    private String buildCoverUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.startsWith("http") ? raw : "https://www.babelio.com" + raw;
    }

    // ── Authentication ───────────────────────────────────────────────────────

    private boolean ensureAuthenticated() throws IOException, InterruptedException {
        if (cachedToken != null && cachedUserId != null) return true;
        synchronized (this) {
            if (cachedToken != null && cachedUserId != null) return true;
            return login();
        }
    }

    private synchronized boolean login() throws IOException, InterruptedException {
        MetadataProviderSettings.Babelio settings = getSettings();
        if (settings == null) return false;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("action", "connect_reader");
        fields.put("user_login", settings.getUserLogin());
        fields.put("password", settings.getPassword());
        fields.put("os", "2");
        String json = sendRequest(fields);
        BabelioLoginResponse resp = objectMapper.readValue(json, BabelioLoginResponse.class);
        if (resp.getSuccess() == 0) {
            log.warn("Babelio: action=connect_reader — login failed: {}", resp.getReason());
            return false;
        }
        cachedToken = resp.getToken();
        cachedUserId = resp.getUserId();
        log.info("Babelio: action=connect_reader — login successful, user_id={}", cachedUserId);
        return true;
    }

    private String computeSessionId(String userId, long timestamp, String action, String token) {
        try {
            String message = userId + timestamp + action;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Babelio session signature", e);
        }
    }

    private boolean isAuthFailure(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            int code = node.path("code").asInt(0);
            int success = node.path("success").asInt(1);
            String reason = node.path("reason").asText("");
            return code == 4 || (success == 0 && reason.contains("authentification failure"));
        } catch (Exception e) {
            return false;
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private String post(Map<String, String> extraFields)
            throws IOException, InterruptedException {
        if (!ensureAuthenticated()) return null;
        return postWithAuth(extraFields, true);
    }

    private String postWithAuth(Map<String, String> extraFields, boolean retry)
            throws IOException, InterruptedException {
        if (cachedToken == null || cachedUserId == null) return null;
        String action = extraFields.get("action");
        long timestamp = System.currentTimeMillis();
        String sessionId = computeSessionId(cachedUserId, timestamp, action, cachedToken);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("user_id", cachedUserId);
        fields.put("session_id", sessionId);
        fields.put("timestamp", String.valueOf(timestamp));
        fields.putAll(extraFields);

        String json = sendRequest(fields);

        if (retry && isAuthFailure(json)) {
            log.warn("Babelio: action={} — auth failure (code 4), re-authenticating and retrying", action);
            synchronized (this) {
                cachedToken = null;
                cachedUserId = null;
                if (!login()) return null;
            }
            return postWithAuth(extraFields, false);
        }
        return json;
    }

    private String sendRequest(Map<String, String> fields) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(buildMultipartBody(fields)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    // ── Settings ─────────────────────────────────────────────────────────────

    private MetadataProviderSettings.Babelio getSettings() {
        MetadataProviderSettings.Babelio settings = appSettingService.getAppSettings()
                .getMetadataProviderSettings().getBabelio();
        if (settings == null
                || settings.getUserLogin() == null || settings.getUserLogin().isBlank()
                || settings.getPassword() == null || settings.getPassword().isBlank()) {
            log.warn("Babelio: credentials not configured");
            return null;
        }
        return settings;
    }

    // ── Parsing helpers ──────────────────────────────────────────────────────

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
