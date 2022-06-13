package shamsasari.wallhavenplugin

import com.fasterxml.jackson.databind.json.JsonMapper
import java.awt.GraphicsEnvironment
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
        val client = HttpClient.newHttpClient()
        val displayMode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode

        val randomWallapersUri = URI("https://wallhaven.cc/api/v1/search?" +
            "atleast=${displayMode.width}x${displayMode.height}&" +
            "q=${URLEncoder.encode(args[0], UTF_8)}&" +
            "sorting=random"
        )
        val request = HttpRequest.newBuilder(randomWallapersUri).GET().build()
        val jsonResponse = client.send(request, BodyHandlers.ofInputStream()).body().use(JsonMapper()::readTree)
        val firstWallpaper = jsonResponse["data"][0]
        val id = firstWallpaper["id"].textValue()
        val path = firstWallpaper["path"].textValue()
        println(firstWallpaper["url"])

        val wallpaperFile = Files.createTempDirectory("wallhaven-plugin") / id

        val wallpaperDownload = client.send(
            HttpRequest.newBuilder(URI(path)).GET().build(),
            BodyHandlers.ofInputStream()
        ).body()

        wallpaperDownload.use { Files.copy(it, wallpaperFile) }

        WindowsOperatingSystem.setWallpaper(wallpaperFile)
    }
}
