import jdk.incubator.foreign.*;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static jdk.incubator.foreign.ValueLayout.*;

public class SetWallpaper {
    private static final int SPI_SETDESKWALLPAPER  = 0x0014;
    private static final int SPIF_UPDATEINIFILE    = 0x01;
    private static final int SPIF_SENDCHANGE       = 0x02;

    private static final MethodHandle systemParametersInfoAFunction;

    static {
        System.loadLibrary("user32");
        // BOOL SystemParametersInfoA(UINT uiAction, UINT uiParam, PVOID pvParam, UINT fWinIni);
        systemParametersInfoAFunction = CLinker.systemCLinker().downcallHandle(
                SymbolLookup.loaderLookup().lookup("SystemParametersInfoA").get(),
                FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        );
    }

    public static void set(Path file) {
        try (ResourceScope scope = ResourceScope.newConfinedScope()) {
            SegmentAllocator allocator = SegmentAllocator.nativeAllocator(scope);
            Addressable cString = allocator.allocateUtf8String(file.toString());
            var result = (boolean)systemParametersInfoAFunction.invokeExact(
                    SPI_SETDESKWALLPAPER,
                    0,
                    cString,
                    SPIF_UPDATEINIFILE | SPIF_SENDCHANGE
            );
            if (!result) {
                throw new IllegalStateException();
            }
        } catch (Error | RuntimeException t) {
            throw t;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}