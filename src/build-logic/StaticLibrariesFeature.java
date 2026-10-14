package ai.enterprise.build;

import com.oracle.svm.core.jdk.NativeLibrarySupport;
import org.graalvm.nativeimage.hosted.Feature;

/** Internal API pinned to Graal 981ecb6e1be6bf4591a6dd24b48ea053221209f5. */
public final class StaticLibrariesFeature implements Feature {
    public void duringSetup(DuringSetupAccess access) {
        var libraries = NativeLibrarySupport.singleton();
        libraries.preregisterUninitializedBuiltinLibrary("enterpriseai_platform");
        libraries.preregisterUninitializedBuiltinLibrary("enterpriseai_swt");
        if (access.findClassByName("ai.enterprise.database.SQLiteNative") != null)
            libraries.preregisterUninitializedBuiltinLibrary("enterpriseai_sqlite");
    }
}
