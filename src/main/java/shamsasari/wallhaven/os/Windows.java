package shamsasari.wallhaven.os;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static shamsasari.wallhaven.os.WindowsBinding.*;

@SuppressWarnings("preview")
public final class Windows implements OperatingSystem {
    static {
        System.loadLibrary("Advapi32");
        System.loadLibrary("user32");
    }

    private static final LazyConstant<Boolean> isDarkMode = LazyConstant.of(Windows::queryDarkMode);

    @Override
    public boolean isDarkMode() {
        return isDarkMode.get();
    }

    private static boolean queryDarkMode() {
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocate(C_INT);
            var cbData = arena.allocate(C_INT);
            cbData.set(C_INT, 0, (int)data.byteSize());
            var errorCode = RegGetValueW(
                    HKEY_CURRENT_USER(),
                    arena.allocateFrom("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", UTF_16LE),
                    arena.allocateFrom("SystemUsesLightTheme", UTF_16LE),
                    RRF_RT_REG_DWORD(),
                    MemorySegment.NULL,
                    data,
                    cbData
            );
            if (errorCode != ERROR_SUCCESS()) {
                throw new IllegalStateException("RegGetValueW failed: " + errorCode);
            }
            return data.get(C_INT, 0) == 0;
        }
    }

    @Override
    public void setWallpaper(byte[] image) {
        Path wallpaperFile;
        try {
            wallpaperFile = Files.createTempFile("wallhaven", ".image");
            Files.write(wallpaperFile, image);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (var arena = Arena.ofConfined()) {
            var result = SystemParametersInfoW(
                    SPI_SETDESKWALLPAPER(),
                    0,
                    arena.allocateFrom(wallpaperFile.toString(), UTF_16LE),
                    SPIF_UPDATEINIFILE() | SPIF_SENDCHANGE()
            );
            if (result == 0) {
                throw new IllegalStateException("Unable to set the wallpaper");
            }
        }
    }
}
