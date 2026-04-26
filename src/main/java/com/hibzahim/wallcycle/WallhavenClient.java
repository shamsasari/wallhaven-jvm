package com.hibzahim.wallcycle;

import com.hibzahim.wallcycle.RestApi.WallpaperAndData;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static com.hibzahim.wallcycle.Main.OS;

public final class WallhavenClient implements AutoCloseable {
    private static final int MAX_REQUESTS_PER_SEC = 45;
    private static final int DARK_MODE_BRIGHTNESS_LIMIT = 50;

    private static final DisplayMode displayMode = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .getDefaultScreenDevice()
            .getDisplayMode();

    private final HttpClient httpClient;
    private final String q;
    private final List<String> excludeSimilarTags;
    private volatile long backoffUntil;
    private RestApi.Meta meta;

    WallhavenClient(String q, List<String> excludeSimilarTags) {
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

        var bytes = httpGet(URI.create(url.toString()));
        var result = Main.jsonMapper.readValue(bytes, RestApi.SearchResult.class);
        meta = result.meta();
        if (result.data() == null) {
            return null;
        }
        return result.data();
    }

    RestApi.Wallpaper getWallpaper(String id) throws IOException, InterruptedException {
        var bytes = httpGet(URI.create("https://wallhaven.cc/api/v1/w/" + id));
        return Main.jsonMapper.readValue(bytes, RestApi.WallpaperResult.class).data();
    }

    WallpaperAndData getMatchingWallpaper(String id) throws IOException, InterruptedException {
        var wallpaper = getWallpaper(id);
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
        var bytes = httpGet(wallpaper.path());
        if (OS.isDarkMode()) {
            var brightness = calculateBrightness(ImageIO.read(new ByteArrayInputStream(bytes)));
            if (brightness > DARK_MODE_BRIGHTNESS_LIMIT) {
                Main.logger.info("{} too bright for dark mode ({})", wallpaper.url(), brightness);
                return null;
            }
        }
        return new WallpaperAndData(wallpaper, bytes);
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

    private static int calculateBrightness(BufferedImage image) {
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
