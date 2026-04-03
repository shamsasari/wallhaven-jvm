package shamsasari.wallhaven;

import shamsasari.wallhaven.RestApi.WallpaperAndData;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

@SuppressWarnings("preview")
public final class WallhavenClient implements AutoCloseable {
    private static final int MAX_REQUESTS_PER_SEC = 45;
    private static final int DARK_MODE_BRIGHTNESS_LIMIT = 50;

    private static final DisplayMode displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
    private static final Supplier<Boolean> isDarkMode = StableValue.supplier(() -> {
        try {
            return isDarkMode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    });

    private final HttpClient httpClient;
    private final String q;
    private final java.util.List<String> excludeSimilarTags;
    private volatile long backoffUntil;
    private RestApi.Meta meta;

    WallhavenClient(String q, java.util.List<String> excludeSimilarTags) {
        httpClient = HttpClient.newBuilder().executor(newVirtualThreadPerTaskExecutor()).build();
        this.q = q;
        this.excludeSimilarTags = excludeSimilarTags;
    }

    List<RestApi.WallpaperInfo> getNextPage() throws IOException, InterruptedException {
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
        var result = Main.jsonMapper.readValue(bytes, RestApi.SearchResult.class);
        meta = result.meta();
        if (result.data() == null) {
            return null;
        }
        return result.data();
    }

    RestApi.Wallpaper getWallpaper(String id) throws IOException, InterruptedException {
        var bytes = httpGet(toUri("https://wallhaven.cc/api/v1/w/" + id));
        return Main.jsonMapper.readValue(bytes, RestApi.WallpaperResult.class).data();
    }

    WallpaperAndData getMatchingWallpaper(String id) throws IOException, InterruptedException {
        RestApi.Wallpaper wallpaper = getWallpaper(id);
        var matching = wallpaper
                .tags()
                .stream()
                .allMatch(tag -> {
                    var normalisedTag = tag.name().toLowerCase();
                    return excludeSimilarTags.stream().noneMatch(normalisedTag::contains);
                });
        if (!matching) {
            Main.logger.info("{} does not match", wallpaper.url());
            return null;
        }
        byte[] bytes = httpGet(wallpaper.path());
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (isDarkMode.get() && Main.calculateBrightness(image) > DARK_MODE_BRIGHTNESS_LIMIT) {
            Main.logger.info("{} too bright for dark mode ({})", wallpaper.url(), Main.calculateBrightness(image));
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
                default ->
                        throw new IOException("Received error code " + response.statusCode() + ": " + response.headers());
            }
        }
    }

    private HttpResponse<byte[]> rawHttpGet(URI url) throws IOException, InterruptedException {
        var backOffDelay = backoffUntil - System.currentTimeMillis();
        if (backOffDelay > 0) {
            Thread.sleep(backOffDelay);
        }
        Main.logger.debug("GET: {}", url);
        return httpClient.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void throttle(HttpResponse<?> response) {
        var retryAfterSecs = response.headers().firstValueAsLong("retry-after").orElse(MAX_REQUESTS_PER_SEC);
        var retryAfterMillis = retryAfterSecs * 1000;
        backoffUntil = System.currentTimeMillis() + retryAfterMillis;
        Main.logger.warn("Throttled, trying again after {}s", retryAfterSecs);
    }

    @Override
    public void close() {
        httpClient.close();
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
}
