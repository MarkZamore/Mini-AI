package ai.enterprise.contracts;

/** Build-time constant for Native Image's two existing linkage profiles. */
public final class NativeLinkage {
    private NativeLinkage() {}
    private static final boolean DLLS = Boolean.getBoolean("enterprise.nativeDlls");
    public static boolean builtin() { return !DLLS; }
}
