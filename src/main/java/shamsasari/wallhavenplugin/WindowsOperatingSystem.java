package shamsasari.wallhavenplugin;

import jdk.incubator.foreign.*;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static jdk.incubator.foreign.ValueLayout.*;

public class WindowsOperatingSystem {
    private static final int SPI_SETDESKWALLPAPER = 0x0014;
    private static final int SPIF_UPDATEINIFILE   = 0x01;
    private static final int SPIF_SENDCHANGE      = 0x02;

    private static final MethodHandle systemParametersInfoAFunction;

    static {
        System.loadLibrary("user32");
        // BOOL SystemParametersInfoA(UINT uiAction, UINT uiParam, PVOID pvParam, UINT fWinIni);
        systemParametersInfoAFunction = CLinker.systemCLinker().downcallHandle(
                SymbolLookup.loaderLookup().lookup("SystemParametersInfoA").get(),
                FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );
    }

    public static void setWallpaper(Path file) {
        try (ResourceScope scope = ResourceScope.newConfinedScope()) {
            SegmentAllocator allocator = SegmentAllocator.nativeAllocator(scope);
            Addressable nativeFilePath = allocator.allocateUtf8String(file.toString());
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
