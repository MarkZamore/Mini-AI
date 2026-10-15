package ai.enterprise.database;

import java.nio.file.Path;
import org.graalvm.nativeimage.ImageInfo;

/** Built-in AOT JNI, or an explicit developer DLL for JVM embedding. No extraction. */
public final class SQLiteNative {
    private SQLiteNative() {}
    public static void load(Path dll) {
        if(ImageInfo.inImageRuntimeCode()&&ai.enterprise.contracts.NativeLinkage.builtin()) System.loadLibrary("enterpriseai_sqlite");
        else System.load(dll.toAbsolutePath().normalize().toString());
    }
    public static native long open(String path);
    public static native void close(long handle);
    public static native void interrupt(long handle);
    public static native String version();
    /** Header row first; values are UTF-16 strings or null. Bindings never enter SQL text. */
    public static native String[][] query(long handle, String sql, String[] parameters);
}
