package shamsasari.wallhaven;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shamsasari.wallhaven.RestApi.WallpaperAndData;
import shamsasari.wallhaven.RestApi.WallpaperInfo;
import shamsasari.wallhaven.os.OperatingSystem;
import shamsasari.wallhaven.os.Windows;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

@SuppressWarnings("preview")
class Main {
    private static final Path appHomeDir = Path.of(System.getProperty("user.home")).resolve("wallhaven-plugin");

    public static final OperatingSystem OS = new Windows();
    public static final JsonMapper jsonMapper = JsonMapper.builder().propertyNamingStrategy(new SnakeCaseStrategy()).build();
    public static final Logger logger;

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
        var config = parseConfig();
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
        OS.setWallpaper(matching.data());
        var upTime = ManagementFactory.getRuntimeMXBean().getUptime();
        logger.info("Wallpaper changed to {} {} ({}ms)", matching.wallpaper().url(), matching.wallpaper().tags(), upTime);
    }

    private static JsonNode parseConfig() throws IOException {
        var configFile = appHomeDir.resolve("wallhaven-plugin.json");
        JsonNode config;
        if (Files.exists(configFile)) {
            config = jsonMapper.readTree(configFile);
        } else {
            Files.createFile(configFile);
            config = jsonMapper.createObjectNode();
        }
        return config;
    }

    private WallpaperAndData getMatchingWallpaper(String q, List<String> excludeSimilarTags) throws IOException, InterruptedException {
        try (var client = new WallhavenClient(q, excludeSimilarTags)) {
            while (true) {
                var page = client.getNextPage();
                if (page == null) {
                    return null;
                }
                var matching = getMatchingWallpaperFromPage(page, client);
                if (matching != null) {
                    return matching;
                }
            }
        }
    }

    private WallpaperAndData getMatchingWallpaperFromPage(List<WallpaperInfo> page,
                                                          WallhavenClient client) throws InterruptedException {
        class NonMatchingWallpaper extends Exception {}

        try (var taskScope = StructuredTaskScope.open(
                Joiner.<WallpaperAndData>anySuccessfulOrThrow(),
                config -> config.withThreadFactory(Thread.ofVirtual().name("fetcher", 0).factory())
        )) {
            for (var wallpaperInfo : page) {
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
}
