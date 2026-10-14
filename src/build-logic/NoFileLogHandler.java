package ai.enterprise.build;
import org.graalvm.nativeimage.LogHandler;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.UnsignedWord;

public final class NoFileLogHandler implements LogHandler {
    public void log(CCharPointer bytes,UnsignedWord length){}
    public void flush(){}
    public void fatalError(){exit(1);}
    @CFunction("exit") static native void exit(int status);
}
