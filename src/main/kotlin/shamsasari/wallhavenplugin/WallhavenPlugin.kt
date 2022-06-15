package shamsasari.wallhavenplugin

import com.fasterxml.jackson.databind.json.JsonMapper
import java.awt.GraphicsEnvironment
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import kotlin.io.path.div

object WallhavenPlugin {
    @JvmStatic
    fun main(args: Array<String>) {
        val httpClient = HttpClient.newHttpClient()
        val displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode

        val randomWallapersUrl = "https://wallhaven.cc/api/v1/search?" +
            "resolutions=${displayMode.width}x${displayMode.height}&" +
            "q=${URLEncoder.encode(args[0], UTF_8)}&" +
            "sorting=random"
        val response = httpClient.get(randomWallapersUrl).use(JsonMapper()::readTree)

        val firstWallpaper = response["data"][0]
        val id = firstWallpaper["id"].textValue()
        val wallpaperUrl = firstWallpaper["path"].textValue()

        val wallpaperFile = Files.createTempDirectory("wallhaven-plugin") / id
        httpClient.get(wallpaperUrl).use { Files.copy(it, wallpaperFile) }

        WindowsOperatingSystem.setWallpaper(wallpaperFile)

        val wallhavenUrl = firstWallpaper["url"]
        val total = response["meta"]["total"].intValue()
        println("$wallhavenUrl from $total")
    }

    private fun HttpClient.get(url: String): InputStream {
        return send(HttpRequest.newBuilder(URI(url)).GET().build(), BodyHandlers.ofInputStream()).body()
    }
}
