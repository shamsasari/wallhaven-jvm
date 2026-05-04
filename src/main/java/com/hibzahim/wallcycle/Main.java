package com.hibzahim.wallcycle;

import com.hibzahim.wallcycle.os.OperatingSystem;
import com.hibzahim.wallcycle.os.Windows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

import static tools.jackson.core.StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION;

@SuppressWarnings("preview")
class Main {
    private static final int DARK_MODE_BRIGHTNESS_LIMIT = 50;

    private static final Path appHomeDir = Path.of(System.getProperty("user.home")).resolve("wallhaven-plugin");
    private static final DisplayMode displayMode = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .getDefaultScreenDevice()
            .getDisplayMode();
    private static final OperatingSystem OS = new Windows();

    public static final Logger logger;

    static {
        System.setProperty("app.home", appHomeDir.toString());
        logger = LogManager.getLogger("MainLog");
    }

    void main() {
        try {
            run();
        } catch (Throwable t) {
            logger.fatal("Wallcycle has crashed", t);
            System.exit(1);
        }
    }

    private void run() throws IOException, InterruptedException {
        logger.info("Started Wallcycle");
        var config = JsonMapper
                .builder()
                .enable(INCLUDE_SOURCE_IN_LOCATION)
                .build()
                .readValue(appHomeDir.resolve("wallhaven-plugin.json"), Config.class);
        var changeInterval = Duration.ofMinutes(config.changeMins);
        var excludeSimilarTags = config
                .excludeSimilarTags
                .stream()
                .map(String::toLowerCase)
                .toList();
        WallhavenClient.Meta previousMeta = null;
        try (var client = new WallhavenClient()) {
            while (true) {
                if (previousMeta != null && previousMeta.isLastPage()) {
                    previousMeta = null;
                    logger.info("Ran out of pages, restarting from the beginning");
                }
                var pageResult = client.getRandomPage(
                        displayMode.getWidth(),
                        displayMode.getHeight(),
                        previousMeta != null ? previousMeta.currentPage() + 1 : 1,
                        null,
                        previousMeta != null ? previousMeta.seed() : null
                );
                previousMeta = pageResult.meta();
                var matching = findMatchingWallpaper(client, pageResult.data(), excludeSimilarTags);
                if (matching != null) {
                    OS.setWallpaper(matching.data());
                    logger.info("Wallpaper changed to {} ({})", matching.wallpaper().url(), matching.wallpaper().tags());
                    Thread.sleep(changeInterval);
                } else {
                    logger.info("No matching wallpaper, searching next page");
                }
            }
        }
    }

    private WallpaperAndData findMatchingWallpaper(WallhavenClient client,
                                                   List<WallhavenClient.PageEntry> pageEntries,
                                                   List<String> excludeSimilarTags) throws InterruptedException {
        class NonMatchingWallpaper extends Exception {}

        try (var taskScope = StructuredTaskScope.open(Joiner.<WallpaperAndData>anySuccessfulOrThrow())) {
            for (var pageEntry : pageEntries) {
                taskScope.fork(() -> {
                    var wallpaper = getMatchingWallpaper(client, pageEntry.id(), excludeSimilarTags);
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
                        return null;
                    }
                    case null, default -> throw e;
                }
            }
        }
    }

    private WallpaperAndData getMatchingWallpaper(
            WallhavenClient client,
            String id,
            List<String> excludeSimilarTags
    ) throws IOException, InterruptedException {
        var wallpaper = client.getWallpaper(id);
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
        var bytes = client.httpGet(wallpaper.path());
        if (OS.isDarkMode()) {
            var brightness = calculateBrightness(ImageIO.read(new ByteArrayInputStream(bytes)));
            if (brightness > DARK_MODE_BRIGHTNESS_LIMIT) {
                logger.info("{} too bright for dark mode ({})", wallpaper.url(), brightness);
                return null;
            }
        }
        return new WallpaperAndData(wallpaper, bytes);
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

    private record WallpaperAndData(WallhavenClient.Wallpaper wallpaper, byte[] data) {}

    private record Config(int changeMins, List<String> excludeSimilarTags) { }
}
