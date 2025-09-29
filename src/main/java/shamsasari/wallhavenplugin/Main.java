package shamsasari.wallhavenplugin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shamsasari.wallhavenplugin.RestApi.Wallpaper;
import shamsasari.wallhavenplugin.RestApi.WallpaperInfo;
import shamsasari.wallhavenplugin.RestApi.WallpaperResult;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("preview")
class Main {
    private static final Path appHomeDir = Path.of(System.getProperty("user.home")).resolve("wallhaven-plugin");
    private static final Logger logger;
    private static final DisplayMode displayMode;
    private static final JsonMapper jsonMapper;

    static {
        System.setProperty("app.home", appHomeDir.toString());
        logger = LogManager.getLogger("MainLog");
        logger.info("Start");
        displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
        jsonMapper = new JsonMapper();
        jsonMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    void main() {
        try {
            run();
        } catch (Throwable t) {
            logger.fatal("Unable to update wallpaper", t);
            System.exit(1);
        }
    }

    private void run() throws IOException, InterruptedException {
        var configFile = appHomeDir.resolve("wallhaven-plugin.json");
        JsonNode config;
        if (Files.exists(configFile)) {
            config = jsonMapper.readTree(configFile.toFile());
        } else {
            Files.createFile(configFile);
            config = jsonMapper.createObjectNode();
        }

        var q = config.path("q").textValue();
        var excludeSimilarTags = config.path("excludeSimilarTags")
                .valueStream()
                .map(tag -> tag.textValue().toLowerCase())
                .toList();

        try (var httpClient = HttpClient.newHttpClient()) {
            var matchingWallpaper = getMatchingWallpaper(httpClient, q, excludeSimilarTags);
            if (matchingWallpaper == null) {
                logger.warn("No results");
                return;
            }
            var wallpaperFile = Files.createTempDirectory("wallhaven-plugin").resolve(matchingWallpaper.id());
            Files.write(wallpaperFile, get(httpClient, matchingWallpaper.path()));
            WindowsOperatingSystem.setWallpaper(wallpaperFile);
            logger.info("Wallpaper set {} {}", matchingWallpaper.url(), matchingWallpaper.tags());
        }
    }

    private Wallpaper getMatchingWallpaper(HttpClient httpClient,
                                           String q,
                                           List<String> excludeSimilarTags) throws IOException, InterruptedException {
        class NonMatchingWallpaper extends Exception {}

        var wallpaperFetcher = new WallpaperFetcher(httpClient, q, excludeSimilarTags);
        while (true) {
            List<WallpaperInfo> wallpaperInfos = wallpaperFetcher.getNextPage();
            if (wallpaperInfos == null) {
                return null;
            }
            try (var taskScope = StructuredTaskScope.open(
                    Joiner.<Wallpaper>anySuccessfulResultOrThrow(),
                    config -> config.withThreadFactory(Thread.ofVirtual().name("fetcher", 0).factory())
            )) {
                for (var wallpaperInfo : wallpaperInfos) {
                    taskScope.fork(() -> {
                        var wallpaper = wallpaperFetcher.getMatchingWallpaper(wallpaperInfo.id());
                        if (wallpaper != null) {
                            return wallpaper;
                        }
                        logger.info("{} does not match", wallpaperInfo.url());
                        throw new NonMatchingWallpaper();
                    });
                }

                try {
                    return taskScope.join();
                } catch (StructuredTaskScope.FailedException e) {
                    switch (e.getCause()) {
                        case NonMatchingWallpaper _ -> logger.info("No matching wallpaper, searching next page...");
                        case null, default -> throw e;
                    }
                }
            }
        }
    }

    private static final class WallpaperFetcher {
        private final HttpClient httpClient;
        private final String q;
        private final List<String> excludeSimilarTags;
        private RestApi.Meta meta;

        private WallpaperFetcher(HttpClient httpClient, String q, List<String> excludeSimilarTags) {
            this.httpClient = httpClient;
            this.q = q;
            this.excludeSimilarTags = excludeSimilarTags;
        }

        List<WallpaperInfo> getNextPage() throws IOException, InterruptedException {
            var page = meta != null ? meta.currentPage() + 1 : 1;
            if (meta != null && page > meta.lastPage()) {
                return null;
            }

            var url = new StringBuilder("https://wallhaven.cc/api/v1/search?sorting=random&atleast=")
                    .append(displayMode.getWidth()).append('x').append(displayMode.getHeight())
                    .append("&page=").append(page);
            if (q != null) {
                url.append("&q=").append(URLEncoder.encode(q, UTF_8));
            }
            if (meta != null) {
                url.append("&seed=").append(meta.seed());
            }

            byte[] bytes = get(httpClient, toUri(url));
            var result = jsonMapper.readValue(bytes, RestApi.SearchResult.class);
            meta = result.meta();
            if (result.data() == null) {
                return null;
            }
            return result.data();
        }

        Wallpaper getWallpaper(String id) throws IOException, InterruptedException {
            var bytes = get(httpClient, toUri("https://wallhaven.cc/api/v1/w/" + id));
            return jsonMapper.readValue(bytes, WallpaperResult.class).data();
        }

        Wallpaper getMatchingWallpaper(String id) throws IOException, InterruptedException {
            Wallpaper wallpaper = getWallpaper(id);
            var matching = wallpaper
                    .tags()
                    .stream()
                    .allMatch(tag -> {
                        var normalisedTag = tag.name().toLowerCase();
                        return excludeSimilarTags.stream().noneMatch(normalisedTag::contains);
                    });
            return matching ? wallpaper : null;
        }
    }

    private static byte[] get(HttpClient httpClient, URI url) throws IOException, InterruptedException {
        logger.debug("GET: {}", url);
        var response = httpClient.send(HttpRequest.newBuilder(url).GET().build(), BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Received error code " + response.statusCode() + ": " + response.headers());
        }
        return response.body();
    }

    private static URI toUri(CharSequence uri) {
        try {
            return new URI(uri.toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
