package org.booklore.service.metadata;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.CoverImage;
import org.booklore.model.dto.request.CoverFetchRequest;
import org.booklore.service.appsettings.AppSettingService;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@AllArgsConstructor
public class DuckDuckGoCoverService implements BookCoverProvider {

    private static final String SEARCH_BASE_URL = "https://duckduckgo.com/?q=";
    private static final String JSON_BASE_URL = "https://duckduckgo.com/i.js?o=json&q=";
    private static final String DEFAULT_COVER_SEARCH_SITES = "amazon.com,goodreads.com";

    private static final Map<String, String[]> LANGUAGE_BOOK_WORDS = Map.ofEntries(
            Map.entry("fr",  new String[]{"livre", "livre audio"}),
            Map.entry("fre", new String[]{"livre", "livre audio"}),
            Map.entry("fra", new String[]{"livre", "livre audio"}),
            Map.entry("de",  new String[]{"Buch", "Hörbuch"}),
            Map.entry("ger", new String[]{"Buch", "Hörbuch"}),
            Map.entry("deu", new String[]{"Buch", "Hörbuch"}),
            Map.entry("es",  new String[]{"libro", "audiolibro"}),
            Map.entry("spa", new String[]{"libro", "audiolibro"}),
            Map.entry("it",  new String[]{"libro", "audiolibro"}),
            Map.entry("ita", new String[]{"libro", "audiolibro"}),
            Map.entry("pt",  new String[]{"livro", "audiolivro"}),
            Map.entry("por", new String[]{"livro", "audiolivro"}),
            Map.entry("nl",  new String[]{"boek", "luisterboek"}),
            Map.entry("nld", new String[]{"boek", "luisterboek"}),
            Map.entry("ja",  new String[]{"本", "オーディオブック"}),
            Map.entry("jpn", new String[]{"本", "オーディオブック"}),
            Map.entry("zh",  new String[]{"书", "有声书"}),
            Map.entry("zho", new String[]{"书", "有声书"})
    );
    private static final String SEARCH_PARAMS_TALL = "&iar=images&iaf=size%3ALarge%2Clayout%3ATall";
    private static final String JSON_PARAMS_TALL = "&iar=images&iaf=size%3ALarge%2Clayout%3ATall";
    private static final String SEARCH_PARAMS_SQUARE = "&iar=images&iaf=size%3ALarge%2Clayout%3ASquare";
    private static final String JSON_PARAMS_SQUARE = "&iar=images&iaf=size%3ALarge%2Clayout%3ASquare";

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String REFERRER = "https://duckduckgo.com/";
    private static final Map<String, String> HTML_HEADERS = Map.ofEntries(
            Map.entry("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"),
            Map.entry("accept-language", "en-US,en;q=0.9"),
            Map.entry("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""),
            Map.entry("sec-ch-ua-mobile", "?0"),
            Map.entry("sec-ch-ua-platform", "\"macOS\""),
            Map.entry("sec-fetch-dest", "document"),
            Map.entry("sec-fetch-mode", "navigate"),
            Map.entry("sec-fetch-site", "same-origin"),
            Map.entry("sec-fetch-user", "?1"),
            Map.entry("upgrade-insecure-requests", "1"),
            Map.entry("user-agent", USER_AGENT)
    );
    private static final Map<String, String> JSON_HEADERS = Map.ofEntries(
            Map.entry("accept", "application/json, text/javascript, */*; q=0.01"),
            Map.entry("accept-language", "en-US,en;q=0.9"),
            Map.entry("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""),
            Map.entry("sec-ch-ua-mobile", "?0"),
            Map.entry("sec-ch-ua-platform", "\"macOS\""),
            Map.entry("sec-fetch-dest", "empty"),
            Map.entry("sec-fetch-mode", "cors"),
            Map.entry("sec-fetch-site", "same-origin"),
            Map.entry("x-requested-with", "XMLHttpRequest"),
            Map.entry("user-agent", USER_AGENT)
    );

    private final ObjectMapper mapper;
    private final AppSettingService appSettingService;

    public Flux<CoverImage> getCovers(CoverFetchRequest request) {
        return Flux.create(sink -> {
            try {
                String title = request.getTitle();
                String author = request.getAuthor();
                boolean isAudiobook = "audiobook".equalsIgnoreCase(request.getCoverType());
                String bookType = resolveBookWord(request.getLanguage(), isAudiobook);
                String searchTerm = (author != null && !author.isEmpty())
                        ? title + " " + author + " " + bookType
                        : title + " " + bookType;
                log.debug("DDG cover search language=\"{}\", bookType=\"{}\"", request.getLanguage(), bookType);

                String searchParams = isAudiobook ? SEARCH_PARAMS_SQUARE : SEARCH_PARAMS_TALL;
                String jsonParams = isAudiobook ? JSON_PARAMS_SQUARE : JSON_PARAMS_TALL;

                String rawSites = appSettingService.getAppSettings().getCoverSearchSites();
                String siteFilter = buildSiteFilter(rawSites);
                log.debug("DDG cover search: term=\"{}\", configuredSites=\"{}\", siteFilter=\"{}\"", searchTerm, rawSites, siteFilter);

                AtomicInteger index = new AtomicInteger(1);
                Set<String> emittedUrls = new HashSet<>();

                // 1. Site-filtered search
                String encodedSiteQuery = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
                String rawSiteFilter = "+" + siteFilter.replace(" ", "+").replace(":", "%3A");
                String siteUrl = SEARCH_BASE_URL + encodedSiteQuery + rawSiteFilter + searchParams;
                log.debug("DDG phase-1 HTML url: {}", siteUrl);
                Connection.Response siteResponse = getResponse(siteUrl);
                Document siteDoc = parseResponse(siteResponse);
                Map<String, String> cookies = siteResponse.cookies();
                Pattern tokenPattern = Pattern.compile("vqd=\"(\\d+-\\d+)\"");
                Matcher siteMatcher = tokenPattern.matcher(siteDoc.html());

                if (siteMatcher.find()) {
                    String siteSearchToken = siteMatcher.group(1);
                    String phase1Query = searchTerm + " " + siteFilter;
                    log.debug("DDG phase-1 token found, API query: \"{}\"", phase1Query);
                    List<CoverImage> siteFilteredImages = fetchImagesFromApi(phase1Query, siteSearchToken, cookies, siteUrl, jsonParams);
                    log.debug("DDG phase-1 raw results: {}", siteFilteredImages.stream().map(i -> i.getWidth() + "x" + i.getHeight() + " " + extractDomain(i.getUrl())).toList());
                    // Phase 1 comes from trusted sites — lower the size bar and skip aspect ratio check
                    siteFilteredImages.removeIf(dto -> dto.getWidth() < 150 || dto.getHeight() < 150);
                    if (isAudiobook) {
                        siteFilteredImages.removeIf(dto -> !isApproximatelySquare(dto.getWidth(), dto.getHeight()));
                    }
                    log.debug("DDG phase-1 after filters: {} results", siteFilteredImages.size());

                    int count = 0;
                    for (CoverImage img : siteFilteredImages) {
                        if (sink.isCancelled()) return;
                        if (count >= 7) break;
                        log.debug("DDG phase-1 emitting: {}", img.getUrl());
                        CoverImage indexedImg = new CoverImage(img.getUrl(), img.getWidth(), img.getHeight(), index.getAndIncrement());
                        sink.next(indexedImg);
                        emittedUrls.add(img.getUrl());
                        count++;
                    }
                } else {
                    log.warn("DDG phase-1: no VQD token found in HTML response");
                }

                if (sink.isCancelled()) return;

                // 2. General search
                String encodedGeneralQuery = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
                String generalUrl = SEARCH_BASE_URL + encodedGeneralQuery + searchParams;
                log.debug("DDG phase-2 HTML url: {}", generalUrl);
                Connection.Response generalResponse = getResponse(generalUrl);
                Document generalDoc = parseResponse(generalResponse);
                Map<String, String> generalCookies = generalResponse.cookies();
                Matcher generalMatcher = tokenPattern.matcher(generalDoc.html());

                if (generalMatcher.find()) {
                    String generalSearchToken = generalMatcher.group(1);
                    List<CoverImage> generalBookImages = fetchImagesFromApi(searchTerm, generalSearchToken, generalCookies, generalUrl, jsonParams);
                    log.debug("DDG phase-2 raw results: {}", generalBookImages.stream().map(i -> extractDomain(i.getUrl())).toList());
                    generalBookImages.removeIf(dto -> dto.getWidth() < 350);
                    if (isAudiobook) {
                        generalBookImages.removeIf(dto -> !isApproximatelySquare(dto.getWidth(), dto.getHeight()));
                    } else {
                        generalBookImages.removeIf(dto -> dto.getWidth() >= dto.getHeight());
                    }
                    generalBookImages.removeIf(dto -> emittedUrls.contains(dto.getUrl()));

                    int count = 0;
                    for (CoverImage img : generalBookImages) {
                        if (sink.isCancelled()) return;
                        if (count >= 10) break;
                        if (emittedUrls.contains(img.getUrl())) continue;
                        log.debug("DDG phase-2 emitting: {}", img.getUrl());
                        CoverImage indexedImg = new CoverImage(img.getUrl(), img.getWidth(), img.getHeight(), index.getAndIncrement());
                        sink.next(indexedImg);
                        emittedUrls.add(img.getUrl());
                        count++;
                    }
                } else {
                    log.warn("DDG phase-2: no VQD token found in HTML response");
                }

                sink.complete();
            } catch (Exception e) {
                log.error("Error in getCovers stream", e);
                sink.error(e);
            }
        });
    }

    public Flux<CoverImage> searchImages(String searchTerm) {
        return Flux.create(sink -> {
            try {
                String searchParams = "&iar=images";
                String jsonParams = "&iar=images";

                String encodedQuery = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
                String searchUrl = SEARCH_BASE_URL + encodedQuery + searchParams;
                Connection.Response response = getResponse(searchUrl);
                Document doc = parseResponse(response);
                Map<String, String> cookies = response.cookies();
                Pattern tokenPattern = Pattern.compile("vqd=\"(\\d+-\\d+)\"");
                Matcher matcher = tokenPattern.matcher(doc.html());

                if (!matcher.find()) {
                    log.error("Could not find search token for image search");
                    sink.complete();
                    return;
                }

                String searchToken = matcher.group(1);
                List<CoverImage> images = fetchImagesFromApi(searchTerm, searchToken, cookies, searchUrl, jsonParams);

                AtomicInteger index = new AtomicInteger(1);
                for (CoverImage img : images) {
                    if (sink.isCancelled()) return;
                    sink.next(new CoverImage(img.getUrl(), img.getWidth(), img.getHeight(), index.getAndIncrement()));
                }

                sink.complete();
            } catch (Exception e) {
                log.error("Error in searchImages stream", e);
                sink.error(e);
            }
        });
    }

    private List<CoverImage> fetchImagesFromApi(String query, String searchToken, Map<String, String> cookies, String referrerUrl, String jsonParams) {
        List<String> configuredSites = parseSites(appSettingService.getAppSettings().getCoverSearchSites());
        List<CoverImage> priority = new ArrayList<>();
        List<CoverImage> others = new ArrayList<>();
        try {
            String url = JSON_BASE_URL
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + jsonParams
                    + "&vqd=" + searchToken;

            Connection.Response resp = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .referrer(referrerUrl)
                    .followRedirects(true)
                    .headers(JSON_HEADERS)
                    .header("x-vqd-4", searchToken)
                    .cookies(cookies)
                    .method(Connection.Method.GET)
                    .execute();

            String json = resp.body();
            JsonNode results = mapper.readTree(json).path("results");
            if (results.isArray()) {
                for (JsonNode img : results) {
                    String link = img.path("image").asText();
                    int w = img.path("width").asInt();
                    int h = img.path("height").asInt();
                    CoverImage dto = new CoverImage(link, w, h, 0);
                    boolean isPriority = configuredSites.stream().anyMatch(link::contains);
                    if (isPriority) {
                        priority.add(dto);
                    } else {
                        others.add(dto);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error fetching images from DuckDuckGo", e);
            throw ApiError.INTERNAL_SERVER_ERROR.createException("DuckDuckGo image fetch failed");
        }
        List<CoverImage> all = new ArrayList<>(priority);
        all.addAll(others);
        return all;
    }

    private String resolveBookWord(String language, boolean isAudiobook) {
        if (language == null || language.isBlank()) {
            return isAudiobook ? "audiobook" : "book";
        }
        String raw = language.trim().toLowerCase();
        // Handle "fr-FR", "fr_FR" style tags — take just the primary subtag
        final String key = (raw.contains("-") || raw.contains("_")) ? raw.split("[-_]")[0] : raw;
        String[] words = LANGUAGE_BOOK_WORDS.get(key);
        if (words == null) {
            words = LANGUAGE_BOOK_WORDS.entrySet().stream()
                    .filter(e -> key.startsWith(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (words == null) {
            return isAudiobook ? "audiobook" : "book";
        }
        return isAudiobook ? words[1] : words[0];
    }

    private String buildSiteFilter(String coverSearchSites) {
        List<String> sites = parseSites(coverSearchSites);
        if (sites.isEmpty()) {
            sites = parseSites(DEFAULT_COVER_SEARCH_SITES);
        }
        String joined = sites.stream()
                .map(s -> "site:" + s)
                .reduce((a, b) -> a + " OR " + b)
                .orElse("");
        return "(" + joined + ")";
    }

    private List<String> parseSites(String coverSearchSites) {
        if (coverSearchSites == null || coverSearchSites.isBlank()) {
            return List.of();
        }
        return Arrays.stream(coverSearchSites.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private Connection.Response getResponse(String url) {
        try {
            return Jsoup.connect(url)
                    .referrer(REFERRER)
                    .followRedirects(true)
                    .headers(HTML_HEADERS)
                    .method(Connection.Method.GET)
                    .execute();
        } catch (IOException e) {
            log.error("Error fetching url: {}", url, e);
            throw ApiError.INTERNAL_SERVER_ERROR.createException("Error fetching URL: " + url);
        }
    }

    private Document parseResponse(Connection.Response response) {
        try {
            return response.parse();
        } catch (IOException e) {
            log.error("Error parsing response", e);
            throw ApiError.INTERNAL_SERVER_ERROR.createException("Error parsing response");
        }
    }

    private boolean isApproximatelySquare(int width, int height) {
        if (width == 0 || height == 0) return false;
        double ratio = (double) width / height;
        return ratio >= 0.85 && ratio <= 1.15;
    }

    private String extractDomain(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
