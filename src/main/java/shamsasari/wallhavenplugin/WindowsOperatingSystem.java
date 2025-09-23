package shamsasari.wallhavenplugin;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

public class WindowsOperatingSystem {
    private static final int SPI_SETDESKWALLPAPER = 0x0014;
    private static final int SPIF_UPDATEINIFILE   = 0x01;
    private static final int SPIF_SENDCHANGE      = 0x02;

    private static final MethodHandle systemParametersInfoAFunction;

    static {
        System.loadLibrary("user32");
        // BOOL SystemParametersInfoA(UINT uiAction, UINT uiParam, PVOID pvParam, UINT fWinIni);
        systemParametersInfoAFunction = Linker.nativeLinker().downcallHandle(
                SymbolLookup.loaderLookup().findOrThrow("SystemParametersInfoA"),
                FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );
    }

    public static void setWallpaper(Path file) {
        try (var arena = Arena.ofConfined()) {
            var nativeFilePath = arena.allocateFrom(file.toString());
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
}
