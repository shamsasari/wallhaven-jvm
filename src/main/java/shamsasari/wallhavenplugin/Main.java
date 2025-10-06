package shamsasari.wallhavenplugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shamsasari.wallhavenplugin.RestApi.Wallpaper;
import shamsasari.wallhavenplugin.RestApi.WallpaperInfo;
import shamsasari.wallhavenplugin.RestApi.WallpaperResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("preview")
class Main {
    private static final int DARK_MODE_BRIGHTNESS_LIMIT = 50;

    private static final long startTime = System.currentTimeMillis();
    private static final Path appHomeDir = Path.of(System.getProperty("user.home")).resolve("wallhaven-plugin");
    private static final JsonMapper jsonMapper = JsonMapper.builder().propertyNamingStrategy(new SnakeCaseStrategy()).build();
    private static final Supplier<Boolean> isDarkMode = StableValue.supplier(() -> {
        try {
            return isDarkMode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    });
    private static final Logger logger;

    static {
        System.setProperty("app.home", appHomeDir.toString());
        logger = LogManager.getLogger("MainLog");
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

        var q = config.path("q").stringValue(null);
        var excludeSimilarTags = config.path("excludeSimilarTags")
                .valueStream()
                .map(tag -> tag.stringValue().toLowerCase())
                .toList();

        var matching = getMatchingWallpaper(q, excludeSimilarTags);
        if (matching == null) {
            logger.warn("No results");
            return;
        }
        var wallpaperFile = Files.createTempDirectory("wallhaven-plugin").resolve(matching.wallpaper.id());
        Files.write(wallpaperFile, matching.data);
        WindowsOperatingSystem.setWallpaper(wallpaperFile);
        var duration = System.currentTimeMillis() - startTime;
        logger.info("Wallpaper changed to {} {} ({}ms)", matching.wallpaper.url(), matching.wallpaper.tags(), duration);
    }

    private static boolean isDarkMode() throws IOException {
        var process = new ProcessBuilder(
                "reg",
                "query",
                "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "SystemUsesLightTheme"
        ).start();
        try (var reader = process.inputReader()) {
            var output = reader
                    .lines()
                    .skip(2)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unable to determine Windows dark mode"));
            String header = "    SystemUsesLightTheme    REG_DWORD    ";
            if (output.length() != header.length() + 3 || !output.startsWith(header)) {
                throw new IllegalStateException("Unable to determine Windows dark mode: " + output);
            }
            if (output.endsWith("0x0")) {
                return true;
            } else if (output.endsWith("0x1")) {
                return false;
            } else {
                throw new IllegalStateException("Unable to determine Windows dark mode: " + output);
            }
        }
    }

    private WallpaperAndData getMatchingWallpaper(String q, List<String> excludeSimilarTags) throws IOException, InterruptedException {
        try (var wallpaperFetcher = new WallhavenClient(q, excludeSimilarTags)) {
            while (true) {
                List<WallpaperInfo> wallpaperInfos = wallpaperFetcher.getNextPage();
                if (wallpaperInfos == null) {
                    return null;
                }
                var matching = getMatchingWallpaperFromPage(wallpaperInfos, wallpaperFetcher);
                if (matching != null) {
                    return matching;
                }
            }
        }
    }

    private WallpaperAndData getMatchingWallpaperFromPage(List<WallpaperInfo> wallpaperInfos,
                                                          WallhavenClient client) throws InterruptedException {
        class NonMatchingWallpaper extends Exception {}

        try (var taskScope = StructuredTaskScope.open(
                Joiner.<WallpaperAndData>anySuccessfulResultOrThrow(),
                config -> config.withThreadFactory(Thread.ofVirtual().name("fetcher", 0).factory())
        )) {
            for (var wallpaperInfo : wallpaperInfos) {
                taskScope.fork(() -> {
                    var wallpaper = client.getMatchingWallpaper(wallpaperInfo.id());
                    if (wallpaper != null) {
                        return wallpaper;
                    }
                    throw new NonMatchingWallpaper();
                });
            }

            try {
                return taskScope.join();
            } catch (StructuredTaskScope.FailedException e) {
                switch (e.getCause()) {
                    case NonMatchingWallpaper _ -> {
                        logger.info("No matching wallpaper, searching next page...");
                        return null;
                    }
                    case null, default -> throw e;
                }
            }
        }
    }

    private record WallpaperAndData(Wallpaper wallpaper, byte[] data) {}

    static final class WallhavenClient implements AutoCloseable {
        private static final int MAX_REQUESTS_PER_SEC = 45;

        private static final DisplayMode displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();

        private final HttpClient httpClient;
        private final String q;
        private final List<String> excludeSimilarTags;
        private volatile long backoffUntil;
        private RestApi.Meta meta;

        WallhavenClient(String q, List<String> excludeSimilarTags) {
            httpClient = HttpClient.newBuilder().executor(Runnable::run).build();
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

            byte[] bytes = httpGet(toUri(url));
            var result = jsonMapper.readValue(bytes, RestApi.SearchResult.class);
            meta = result.meta();
            if (result.data() == null) {
                return null;
            }
            return result.data();
        }

        Wallpaper getWallpaper(String id) throws IOException, InterruptedException {
            var bytes = httpGet(toUri("https://wallhaven.cc/api/v1/w/" + id));
            return jsonMapper.readValue(bytes, WallpaperResult.class).data();
        }

        WallpaperAndData getMatchingWallpaper(String id) throws IOException, InterruptedException {
            Wallpaper wallpaper = getWallpaper(id);
            var matching = wallpaper
                    .tags()
                    .stream()
                    .allMatch(tag -> {
                        var normalisedTag = tag.name().toLowerCase();
                        return excludeSimilarTags.stream().noneMatch(normalisedTag::contains);
                    });
            if (!matching) {
                logger.info("{} does not match", wallpaper.url());
                return null;
            }
            byte[] bytes = httpGet(wallpaper.path());
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (isDarkMode.get() && calculateBrightness(image) > DARK_MODE_BRIGHTNESS_LIMIT) {
                logger.info("{} too bright for dark mode ({})", wallpaper.url(), calculateBrightness(image));
                return null;
            }
            return new WallpaperAndData(wallpaper, bytes);
        }

        private static URI toUri(CharSequence uri) {
            try {
                return new URI(uri.toString());
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }

        private byte[] httpGet(URI url) throws IOException, InterruptedException {
            while (true) {
                var response = rawHttpGet(url);
                switch (response.statusCode()) {
                    case 200 -> {
                        return response.body();
                    }
                    case 429 -> throttle(response);
                    default -> throw new IOException("Received error code " + response.statusCode() + ": " + response.headers());
                }
            }
        }

        private HttpResponse<byte[]> rawHttpGet(URI url) throws IOException, InterruptedException {
            var backOffDelay = backoffUntil - System.currentTimeMillis();
            if (backOffDelay > 0) {
                Thread.sleep(backOffDelay);
            }
            logger.debug("GET: {}", url);
            return httpClient.send(HttpRequest.newBuilder(url).GET().build(), BodyHandlers.ofByteArray());
        }

        private void throttle(HttpResponse<?> response) {
            var retryAfterSecs = response.headers().firstValueAsLong("retry-after").orElse(MAX_REQUESTS_PER_SEC);
            var retryAfterMillis = retryAfterSecs * 1000;
            backoffUntil = System.currentTimeMillis() + retryAfterMillis;
            logger.warn("Throttled, trying again after {}s", retryAfterSecs);
        }

        @Override
        public void close() {
            httpClient.close();
        }
    }

    public static int calculateBrightness(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        long totalBrightness = 0;
        int pixelCount = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);

                // Extract RGB components
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // Calculate luminance using standard formula
                int luminance = (int)(0.299 * red + 0.587 * green + 0.114 * blue);
                totalBrightness += luminance;
            }
        }

        return (int)(totalBrightness / pixelCount);
    }
}
