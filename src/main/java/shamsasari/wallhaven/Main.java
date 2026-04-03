package shamsasari.wallhaven;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shamsasari.wallhaven.RestApi.WallpaperAndData;
import shamsasari.wallhaven.RestApi.WallpaperInfo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
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
        var wallpaperFile = Files.createTempDirectory("wallhaven-plugin").resolve(matching.wallpaper().id());
        Files.write(wallpaperFile, matching.data());
        WindowsOperatingSystem.setWallpaper(wallpaperFile);
        var upTime = ManagementFactory.getRuntimeMXBean().getUptime();
        logger.info("Wallpaper changed to {} {} ({}ms)", matching.wallpaper().url(), matching.wallpaper().tags(), upTime);
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
