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
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;

class Main {
    private static final DisplayMode displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
    private static final Path appHomeDir = Path.of(System.getProperty("user.home")).resolve("wallhaven-plugin");
    @SuppressWarnings("preview")
    private static final Supplier<Logger> logger = StableValue.supplier(() -> {
        System.setProperty("app.home", appHomeDir.toString());
        return LogManager.getLogger("MainLog");
    });
    private static final JsonMapper jsonMapper = new JsonMapper();

    static {
        jsonMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    void main() throws IOException {
        Files.createDirectories(appHomeDir);
        try {
            run();
        } catch (Throwable t) {
            logger().fatal("Unable to update wallpaper", t);
            System.exit(1);
        }
    }

    private void run() throws IOException {
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
                logger().warn("No results");
                return;
            }
            logger().info("Setting wallpaper {} {}", matchingWallpaper.url(), matchingWallpaper.tags());
            var wallpaperFile = Files.createTempDirectory("wallhaven-plugin").resolve(matchingWallpaper.id());
            Files.write(wallpaperFile, get(httpClient, matchingWallpaper.path()));
            WindowsOperatingSystem.setWallpaper(wallpaperFile);
        }
    }

    private Wallpaper getMatchingWallpaper(HttpClient httpClient, String q, List<String> excludeSimilarTags) throws IOException {
        var wallpaperFetcher = new WallpaperFetcher(httpClient, q);
        while (true) {
            List<WallpaperInfo> wallpaperInfos = wallpaperFetcher.getNextWallpaperPage();
            if (wallpaperInfos == null) {
                return null;
            }
            for (var wallpaperInfo : wallpaperInfos) {
                var wallpaper = wallpaperFetcher.getWallpaper(wallpaperInfo.id());
                var matching = wallpaper.tags()
                        .stream()
                        .allMatch(tag -> {
                            var normalisedTag = tag.name().toLowerCase();
                            return excludeSimilarTags.stream().noneMatch(normalisedTag::contains);
                        });
                if (matching) {
                    return wallpaper;
                }
                logger().info("{} does not match", wallpaper.url());
            }
            logger().info("No matching wallpaper, searching next page...");
        }
    }

    private static final class WallpaperFetcher {
        private final HttpClient httpClient;
        private final String q;
        private RestApi.Meta meta;

        private WallpaperFetcher(HttpClient httpClient, String q) {
            this.httpClient = httpClient;
            this.q = q;
        }

        List<WallpaperInfo> getNextWallpaperPage() throws IOException {
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

        Wallpaper getWallpaper(String id) throws IOException {
            var bytes = get(httpClient, toUri("https://wallhaven.cc/api/v1/w/" + id));
            return jsonMapper.readValue(bytes, WallpaperResult.class).data();
        }
    }

    private static byte[] get(HttpClient httpClient, URI url) throws IOException {
        try {
            return httpClient.send(HttpRequest.newBuilder(url).GET().build(), BodyHandlers.ofByteArray()).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static URI toUri(CharSequence uri) {
        try {
            return new URI(uri.toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Logger logger() {
        return logger.get();
    }
}
