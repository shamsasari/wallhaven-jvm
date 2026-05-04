package com.hibzahim.wallcycle.os;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.hibzahim.wallcycle.os.WindowsBinding.*;
import static java.nio.charset.StandardCharsets.UTF_16LE;

public final class Windows implements OperatingSystem {
    static {
        System.loadLibrary("Advapi32");
        System.loadLibrary("user32");
    }

    private static final MemorySegment KEY = Arena.global().allocateFrom("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", UTF_16LE);
    private static final MemorySegment VALUE = Arena.global().allocateFrom("SystemUsesLightTheme", UTF_16LE);
    private static final MemorySegment CB_DATA = Arena.global().allocate(C_INT);

    static {
        CB_DATA.set(C_INT, 0, (int)C_INT.byteSize());
    }

    @Override
    public boolean isDarkMode() {
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocate(C_INT);
            var errorCode = RegGetValueW(
                    HKEY_CURRENT_USER(),
                    KEY,
                    VALUE,
                    RRF_RT_REG_DWORD(),
                    MemorySegment.NULL,
                    data,
                    CB_DATA
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
            wallpaperFile = Files.createTempFile("wallcycle", ".image");
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
