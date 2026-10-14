package ai.enterprise.build;
import org.graalvm.nativeimage.*;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.UnsignedWord;

/** Fatal native-runtime diagnostics never create files. No allocation in handler. */
@Platforms(Platform.HOSTED_ONLY.class)
public final class NoFileLogFeature implements Feature {
    public void duringSetup(DuringSetupAccess access){ImageSingletons.add(LogHandler.class,new NoFileLogHandler());}
}
