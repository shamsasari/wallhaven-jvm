package shamsasari.wallhavenplugin

import com.fasterxml.jackson.databind.json.JsonMapper
import org.apache.logging.log4j.LogManager
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
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.io.path.*

object WallhavenPlugin {
    @JvmStatic
    fun main(args: Array<String>) {
        val appHomeDir = Path.of(System.getProperty("user.home")) / "wallhaven-plugin"
        appHomeDir.createDirectories()

        System.setProperty("app.home", appHomeDir.toString())

        val config = Properties()
        val configFile = appHomeDir / "wallhaven-plugin.properties"
        if (configFile.exists()) {
            configFile.inputStream().use(config::load)
        } else {
            configFile.createFile()
        }

        val httpClient = HttpClient.newHttpClient()
        val displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode

        val randomWallpapersUrl = buildString {
            append("https://wallhaven.cc/api/v1/search?resolutions=")
                .append(displayMode.width).append('x').append(displayMode.height)
                .append("&sorting=random")
            val q = config.getProperty("q")
            if (q != null) {
                append("&q=").append(URLEncoder.encode(q, UTF_8))
            }
        }

        val mapper = JsonMapper()
        val response = httpClient.get(randomWallpapersUrl).use(mapper::readTree)

        val firstWallpaper = response["data"][0]
        println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(firstWallpaper))
        val id = firstWallpaper["id"].textValue()
        val wallpaperUrl = firstWallpaper["path"].textValue()

        val setWallpaperFuture = CompletableFuture.runAsync {
            val wallpaperFile = Files.createTempDirectory("wallhaven-plugin") / id
            httpClient.get(wallpaperUrl).use { Files.copy(it, wallpaperFile) }
            WindowsOperatingSystem.setWallpaper(wallpaperFile)
        }

        val tags = httpClient.get("https://wallhaven.cc/api/v1/w/$id").use(mapper::readTree)["data"]["tags"]
        val tagsString = tags.joinToString(prefix = "[", postfix = "]") {
            "${it["name"].textValue()} (${it["id"].intValue()})"
        }

        val wallhavenUrl = firstWallpaper["url"]
        val total = response["meta"]["total"].intValue()

        LogManager.getLogger("MainLog").info("$wallhavenUrl from $total matching wallpapers $tagsString")

        setWallpaperFuture.join()
    }

    private fun HttpClient.get(url: String): InputStream {
        return send(HttpRequest.newBuilder(URI(url)).GET().build(), BodyHandlers.ofInputStream()).body()
    }
}
