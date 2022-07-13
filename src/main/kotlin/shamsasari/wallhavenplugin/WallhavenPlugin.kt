package shamsasari.wallhavenplugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.awt.GraphicsEnvironment
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.*
import kotlin.system.exitProcess

object WallhavenPlugin {
    private lateinit var logger: Logger
    private val jsonMapper = JsonMapper()

    @JvmStatic
    fun main(args: Array<String>) {
        val appHomeDir = Path.of(System.getProperty("user.home")) / "wallhaven-plugin"
        appHomeDir.createDirectories()

        System.setProperty("app.home", appHomeDir.toString())
        logger = LogManager.getLogger("MainLog")

        try {
            run(appHomeDir)
        } catch (t: Throwable) {
            logger.fatal("Unable to update wallpaper", t)
            exitProcess(1)
        }
    }

    private fun run(appHomeDir: Path) {
        val configFile = appHomeDir / "wallhaven-plugin.json"
        val config = if (configFile.exists()) {
            jsonMapper.readTree(configFile.toFile())
        } else {
            configFile.createFile()
            jsonMapper.createObjectNode()
        }

        val httpClient = HttpClient.newHttpClient()

        val matchingWallpaper = findMatchingWallpaper(config, httpClient)
        if (matchingWallpaper == null) {
            logger.warn("No results")
            return
        }

        val id = matchingWallpaper["id"].textValue()
        val wallpaperUrl = matchingWallpaper["path"].textValue()

        val setWallpaperFuture = CompletableFuture.runAsync {
            val wallpaperFile = Files.createTempDirectory("wallhaven-plugin") / id
            httpClient.get(wallpaperUrl).use { Files.copy(it, wallpaperFile) }
            WindowsOperatingSystem.setWallpaper(wallpaperFile)
        }

        val tagsString = httpClient.getWallpaperTags(id).joinToString(prefix = "[", postfix = "]") {
            "${it["name"].textValue()} (${it["id"].intValue()})"
        }

        logger.info("${matchingWallpaper["url"]} $tagsString")

        setWallpaperFuture.join()
    }

    private fun findMatchingWallpaper(config: JsonNode, httpClient: HttpClient): JsonNode? {
        val displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode

        val q = config["q"]?.textValue()?.let { URLEncoder.encode(it, UTF_8) }
        val excludeSimilarTags = config["excludeSimilarTags"]?.map { it.textValue().lowercase() } ?: emptyList()

        var page = 0
        var seed: String? = null
        var lastPage: Int? = null
        while (true) {
            page++
            if (lastPage != null && page > lastPage) {
                return null
            }

            val randomWallpapersUrl = buildString {
                append("https://wallhaven.cc/api/v1/search?resolutions=")
                    .append(displayMode.width).append('x').append(displayMode.height)
                    .append("&sorting=random")
                    .append("&page=").append(page)
                if (q != null) {
                    append("&q=").append(URLEncoder.encode(q, UTF_8))
                }
                if (seed != null) {
                    append("&seed=").append(seed)
                }
            }

            val response = httpClient.getJson(randomWallpapersUrl)

            val data = response["data"] ?: return null

            if (excludeSimilarTags.isEmpty()) {
                return data[0]
            } else {
                if (seed == null) {
                    seed = response["meta"]["seed"].textValue()
                }
                if (lastPage == null) {
                    lastPage = response["meta"]["last_page"].intValue()
                }
                for (json in data) {
                    val id = json["id"].textValue()
                    val matching = httpClient.getWallpaperTags(id).all { tag ->
                        val tagName = tag["name"].textValue().lowercase()
                        excludeSimilarTags.all { it !in tagName }
                    }
                    if (matching) {
                        return json
                    }
                    logger.info("${json["url"]} does not match")
                }
                logger.info("No matching wallpaper, searching on next page...")
            }
        }
    }

    private fun HttpClient.getWallpaperTags(id: String): JsonNode = getWallpaperInfo(id)["data"]["tags"]

    private fun HttpClient.getWallpaperInfo(id: String): JsonNode = getJson("https://wallhaven.cc/api/v1/w/$id")

    private fun HttpClient.get(url: String): InputStream {
        return send(HttpRequest.newBuilder(URI(url)).GET().build(), BodyHandlers.ofInputStream()).body()
    }

    private fun HttpClient.getJson(url: String): JsonNode {
        val body = get(url).use { it.readAllBytes() }
        try {
            return jsonMapper.readTree(body)
        } catch (e: Exception) {
            throw IllegalStateException(body.decodeToString(), e)
        }
    }
}
