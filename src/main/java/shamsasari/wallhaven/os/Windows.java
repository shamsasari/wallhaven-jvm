package shamsasari.wallhaven.os;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;

import static java.lang.foreign.ValueLayout.*;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static shamsasari.wallhaven.os.WindowsBinding.*;

@SuppressWarnings("preview")
public final class Windows implements OperatingSystem {
    private static final int SPI_SETDESKWALLPAPER = 0x0014;
    private static final int SPIF_UPDATEINIFILE   = 0x01;
    private static final int SPIF_SENDCHANGE      = 0x02;

    private static final MethodHandle systemParametersInfoAFunction;

    static {
        System.loadLibrary("user32");
        System.loadLibrary("Advapi32");
        // BOOL SystemParametersInfoA(UINT uiAction, UINT uiParam, PVOID pvParam, UINT fWinIni);
        systemParametersInfoAFunction = Linker.nativeLinker().downcallHandle(
                SymbolLookup.loaderLookup().findOrThrow("SystemParametersInfoA"),
                FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );
    }

    private static final LazyConstant<Boolean> isDarkMode = LazyConstant.of(Windows::queryDarkMode);

    @Override
    public boolean isDarkMode() {
        return isDarkMode.get();
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
}
