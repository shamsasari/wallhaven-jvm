package shamsasari.wallhaven.os;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;

import static java.lang.foreign.ValueLayout.*;

@SuppressWarnings("preview")
public final class Windows implements OperatingSystem {
    private static final int SPI_SETDESKWALLPAPER = 0x0014;
    private static final int SPIF_UPDATEINIFILE   = 0x01;
    private static final int SPIF_SENDCHANGE      = 0x02;

    private static final MethodHandle systemParametersInfoAFunction;
    private static final LazyConstant<Boolean> isDarkModeConstant = LazyConstant.of(() -> {
        try {
            return calculateDarkMode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    });

    static {
        System.loadLibrary("user32");
        // BOOL SystemParametersInfoA(UINT uiAction, UINT uiParam, PVOID pvParam, UINT fWinIni);
        systemParametersInfoAFunction = Linker.nativeLinker().downcallHandle(
                SymbolLookup.loaderLookup().findOrThrow("SystemParametersInfoA"),
                FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );
    }

    @Override
    public boolean isDarkMode() {
        return isDarkModeConstant.get();
    }

    @Override
    public void setWallpaper(byte[] image) {
        try (var arena = Arena.ofConfined()) {
            var wallpaperFile = Files.createTempFile("wallhaven", ".image");
            Files.write(wallpaperFile, image);
            var nativeFilePath = arena.allocateFrom(wallpaperFile.toString());
            var result = (boolean)systemParametersInfoAFunction.invokeExact(
                    SPI_SETDESKWALLPAPER,
                    0,
                    nativeFilePath,
                    SPIF_UPDATEINIFILE | SPIF_SENDCHANGE
            );
            if (!result) {
                throw new IllegalStateException("Unable to set the wallpaper");
            }
        } catch (Error | RuntimeException t) {
            throw t;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static boolean calculateDarkMode() throws IOException {
        var processBuilder = new ProcessBuilder(
                "reg",
                "query",
                "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "SystemUsesLightTheme"
        );
        try (var process = processBuilder.start();
             var reader = process.inputReader()
        ) {
            var output = reader
                    .lines()
                    .skip(2)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unable to determine Windows dark mode"));
            String header = "    SystemUsesLightTheme    REG_DWORD    ";
            if (output.length() != header.length() + 3 || !output.startsWith(header)) {
                throw new IllegalStateException("Unable to determine Windows dark mode: " + output);
            }
            if (output.endsWith("0x0")) {
                return true;
            } else if (output.endsWith("0x1")) {
                return false;
            } else {
                throw new IllegalStateException("Unable to determine Windows dark mode: " + output);
            }
        }
    }
}
