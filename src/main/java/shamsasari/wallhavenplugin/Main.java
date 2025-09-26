package shamsasari.wallhavenplugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
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
    private static final Path appHomeDir = Path.of(System.getProperty("user.home")).resolve("wallhaven-plugin");
    @SuppressWarnings("preview")
    private static final Supplier<Logger> logger = StableValue.supplier(() -> {
        System.setProperty("app.home", appHomeDir.toString());
        return LogManager.getLogger("MainLog");
    });
    private static final JsonMapper jsonMapper = new JsonMapper();

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

        try (var httpClient = HttpClient.newHttpClient()) {
            var matchingWallpaper = findMatchingWallpaper(httpClient, config);
            if (matchingWallpaper == null) {
                logger().warn("No results");
                return;
            }

            var id = matchingWallpaper.get("id").textValue();
            var wallpaperUrl = toUri(matchingWallpaper.get("path").textValue());

            var wallpaperFile = Files.createTempDirectory("wallhaven-plugin").resolve(id);
            Files.copy(get(httpClient, wallpaperUrl), wallpaperFile);
            WindowsOperatingSystem.setWallpaper(wallpaperFile);
        }
    }

    private JsonNode findMatchingWallpaper(HttpClient httpClient, JsonNode config) throws IOException {
        var displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();

        var q = config.get("q");
        var excludeSimilarTags = config.path("excludeSimilarTags")
                .valueStream()
                .map(tag -> tag.textValue().toLowerCase())
                .toList();

        var page = 0;
        String seed = null;
        Integer lastPage = null;

        while (true) {
            page++;
            if (lastPage != null && page > lastPage) {
                return null;
            }

            var randomWallpapersUrl = new StringBuilder("https://wallhaven.cc/api/v1/search?sorting=random&atleast=")
                    .append(displayMode.getWidth()).append('x').append(displayMode.getHeight())
                    .append("&page=").append(page);
            if (q != null) {
                randomWallpapersUrl.append("&q=").append(URLEncoder.encode(q.textValue(), UTF_8));
            }
            if (seed != null) {
                randomWallpapersUrl.append("&seed=").append(seed);
            }

            var response = getJson(httpClient, toUri(randomWallpapersUrl));
            var data = response.get("data");
            if (data == null) {
                return null;
            }

            if (excludeSimilarTags.isEmpty()) {
                return data.get(0);
            } else {
                if (seed == null) {
                    seed = response.get("meta").get("seed").textValue();
                }
                if (lastPage == null) {
                    lastPage = response.get("meta").get("last_page").intValue();
                }
                for (var json : data) {
                    var id = json.get("id").textValue();
                    List<String> tags = getWallpaperTags(httpClient, id);
                    var matching = tags.stream().allMatch(tag -> excludeSimilarTags.stream().noneMatch(tag::contains));
                    if (matching) {
                        logger().info("Setting wallpaper {} {}", json.get("url"), tags);
                        return json;
                    }
                    logger().info("{} does not match", json.get("url"));
                }
                logger().info("No matching wallpaper, searching on next page...");
            }
        }
    }

    private List<String> getWallpaperTags(HttpClient httpClient, String id) throws IOException {
        return getWallpaperInfo(httpClient, id).get("data").get("tags")
                .valueStream()
                .map(tag -> tag.get("name").textValue().toLowerCase())
                .toList();
    }

    private JsonNode getWallpaperInfo(HttpClient httpClient, String id) throws IOException {
        return getJson(httpClient, toUri("https://wallhaven.cc/api/v1/w/" + id));
    }

    private JsonNode getJson(HttpClient httpClient, URI url) throws IOException {
        try (var input = get(httpClient, url)) {
            var body = input.readAllBytes();
            try {
                return jsonMapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException(new String(body), e);
            }
        }
    }

    private InputStream get(HttpClient httpClient, URI url) throws IOException {
        try {
            return httpClient.send(HttpRequest.newBuilder(url).GET().build(), BodyHandlers.ofInputStream()).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI toUri(CharSequence uri) {
        try {
            return new URI(uri.toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Logger logger() {
        return logger.get();
    }
}
