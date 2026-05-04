package com.hibzahim.wallcycle;

import io.github.bucket4j.Bucket;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.json.JsonMapper;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofMinutes;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

public final class WallhavenClient implements AutoCloseable {
    private static final int MAX_REQUESTS_PER_MIN = 45;
    private static final int DEFAULT_RETRY_AFTER_SECS = 10;

    private static final JsonMapper jsonMapper = JsonMapper
            .builder()
            .propertyNamingStrategy(new SnakeCaseStrategy())
            .build();
    private static final Bucket rateLimitingBucket = Bucket
            .builder()
            .addLimit((bandwidth) ->
                    bandwidth.capacity(MAX_REQUESTS_PER_MIN).refillIntervally(MAX_REQUESTS_PER_MIN, ofMinutes(1))
            )
            .build();

    private final HttpClient httpClient = HttpClient.newBuilder().executor(newVirtualThreadPerTaskExecutor()).build();

    private volatile long backoffUntil;

    public PageResult getRandomPage(int atleastWidth,
                                    int atleastHeight,
                                    int page,
                                    String q,
                                    String seed) throws IOException, InterruptedException {
        if (page < 1) {
            throw new IllegalArgumentException();
        }
        var urlBuilder = new StringBuilder("https://wallhaven.cc/api/v1/search?sorting=random&atleast=")
                .append(atleastWidth).append('x').append(atleastHeight)
                .append("&page=").append(page);
        if (q != null) {
            urlBuilder.append("&q=").append(URLEncoder.encode(q, UTF_8));
        }
        if (seed != null) {
            urlBuilder.append("&seed=").append(seed);
        }
        var bytes = httpGet(URI.create(urlBuilder.toString()));
        return jsonMapper.readValue(bytes, PageResult.class);
    }

    public Wallpaper getWallpaper(String id) throws IOException, InterruptedException {
        var bytes = httpGet(URI.create("https://wallhaven.cc/api/v1/w/" + id));
        return jsonMapper.readValue(bytes, WallpaperResult.class).data();
    }

    public byte[] httpGet(URI url) throws IOException, InterruptedException {
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
        rateLimitingBucket.asBlocking().consume(1);
        Main.logger.debug("GET: {}", url);
        return httpClient.send(HttpRequest.newBuilder(url).GET().build(), BodyHandlers.ofByteArray());
    }

    private void throttle(HttpResponse<?> response) {
        var retryAfterSecs = response.headers().firstValueAsLong("retry-after").orElse(DEFAULT_RETRY_AFTER_SECS);
        var retryAfterMillis = retryAfterSecs * 1000;
        backoffUntil = System.currentTimeMillis() + retryAfterMillis;
        Main.logger.warn("Throttled, trying again after {}s", retryAfterSecs);
    }

    @Override
    public void close() {
        httpClient.close();
    }

    public record PageResult(List<PageEntry> data, Meta meta) {
        public PageResult {
            data = List.copyOf(data);
        }
    }

    public record PageEntry(String id, URI url, URI path) {}

    public record Meta(
            int currentPage,
            int lastPage,
            String seed
    ) {
        public boolean isLastPage() {
            return currentPage == lastPage;
        }
    }

    public record Wallpaper(
            String id,
            URI url,
            URI path,
            List<Tag> tags
    ) {
        public Wallpaper {
            tags = List.copyOf(tags);
        }
    }

    public record Tag(String name) {
        @Nonnull
        @Override
        public String toString() {
            return name;
        }
    }

    private record WallpaperResult(Wallpaper data) {}
}
