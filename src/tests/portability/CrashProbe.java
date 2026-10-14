import java.nio.file.Path;

/** Developer-only fatal/OOM probe. Never included in an application source set. */
public final class CrashProbe {
    private static native void fatal();
    public static void main(String[] args){
        if(args.length>1&&args[1].equals("oom")){
            try {byte[] impossible=new byte[256*1024*1024];System.out.println(impossible.length);System.exit(2);}
            catch(OutOfMemoryError expected){System.out.println("Expected memory allocation refused");return;}
        }
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        fatal();
        System.exit(3);
    }
}
