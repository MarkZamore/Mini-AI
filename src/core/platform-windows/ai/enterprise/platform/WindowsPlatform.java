package ai.enterprise.platform;

import ai.enterprise.contracts.Api;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ProcessProperties;

/** Windows bridge. AOT uses built-in JNI; JVM embedding uses an explicit developer DLL. */
public final class WindowsPlatform implements Api.Platform {
    private final Path root;
    private final java.util.Set<WorkerProcess> workers=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean closed;
    private static volatile boolean loaded;
    public static Path determineRoot() {
        if(ImageInfo.inImageRuntimeCode()) return Path.of(ProcessProperties.getExecutableName()).toAbsolutePath().getParent();
        String configured=System.getProperty("enterprise.root");
        if(configured==null || configured.isBlank()) throw new IllegalStateException("Launcher did not provide its directory");
        return Path.of(configured).toAbsolutePath().normalize();
    }
    public WindowsPlatform(Path root) {
        this.root=root.toAbsolutePath().normalize();
        synchronized(WindowsPlatform.class) {
            if(!loaded) {
                if(ImageInfo.inImageRuntimeCode()) System.loadLibrary("enterpriseai_platform");
                else {
                    Path library=this.root.resolve("native/enterpriseai-platform.dll");
                    if(!Files.isRegularFile(library)) throw new Api.ServiceException("NATIVE_MISSING","Не найдена библиотека: "+library.getFileName());
                    System.load(library.toString());
                }
                configureNative();
                loaded=true;
            }
        }
    }
    public Path executablePath() { return Path.of(executableNative()); }
    /** Native PE startup receives narrow CRT argv; recover the original UTF-16 command line. */
    public static String[] commandLineArguments(String[] fallback){return ImageInfo.inImageRuntimeCode()?argumentsNative():fallback;}
    public long availableMemoryBytes() { return memoryNative(); }
    public boolean cpuSupports(String isa) { return cpuNative(isa); }
    /** Windows 11 modern text input rejects host image paths >=256 UTF-16 units.
     * The standalone launcher invokes this only on the thread that will create its UI.
     * Ordinary Unicode keyboard input/clipboard work; composition through any IME is unavailable
     * on this thread only when the executable has a long path. No system setting changes.
     */
    public String prepareDesktopUi() {
        int mode=prepareDesktopNative();
        if(mode<0)throw new Api.ServiceException("LONG_PATH_UI","Windows не удалось подготовить ввод для длинного пути приложения");
        return mode==0?"":"Длинный путь приложения: русский, казахский и Unicode-ввод доступны. Составной ввод через IME (например, китайский или японский) в этом окне отключён для совместимости с Windows; используйте обычную раскладку или вставку текста.";
    }
    public void openWith(Path original) { int result=openWithNative(original.toAbsolutePath().toString()); if(result<0) throw new Api.ServiceException("VIEWER","Windows не удалось открыть документ ("+Integer.toHexString(result)+")"); }
    public synchronized Process launchWorker(Path executable,Path directory,long maxMemoryBytes) {
        if(closed)throw new Api.ServiceException("PLATFORM_CLOSED","Приложение завершает работу");
        try { var worker=new WorkerProcess(startNative(executable.toAbsolutePath().toString(),directory.toAbsolutePath().toString(),maxMemoryBytes),workers);workers.add(worker);return worker; }
        catch(RuntimeException e) { throw new Api.ServiceException("WORKER_START","Не удалось запустить локальную модель: "+e.getMessage()); }
    }
    public synchronized void close(){if(closed)return;closed=true;for(var worker:java.util.List.copyOf(workers))worker.destroy();workers.clear();}
    private static native void configureNative();
    private static native String executableNative();
    private static native String[] argumentsNative();
    private static native long memoryNative();
    private static native boolean cpuNative(String isa);
    private static native int prepareDesktopNative();
    private static native int openWithNative(String path);
    private static native long[] startNative(String exe,String directory,long memoryLimit);
    private static native int readNative(long handle,byte[] bytes,int offset,int length);
    private static native void writeNative(long handle,byte[] bytes,int offset,int length);
    private static native int waitNative(long handle,long milliseconds);
    private static native boolean aliveNative(long handle);
    private static native void terminateNative(long handle);
    private static native void closeNative(long handle);
    private static final class PipeInput extends InputStream {
        private long handle;
        PipeInput(long handle) { this.handle=handle; }
        public int read() throws IOException { byte[] b=new byte[1]; return read(b,0,1)==-1?-1:b[0]&255; }
        public int read(byte[] b,int o,int n) throws IOException { java.util.Objects.checkFromIndexSize(o,n,b.length); if(n==0)return 0; long h=handle;if(h==0)return -1;try{return readNative(h,b,o,n);}catch(RuntimeException e){throw new IOException(e);} }
        public synchronized void close() { if(handle!=0){closeNative(handle);handle=0;} }
    }
    private static final class PipeOutput extends OutputStream {
        private long handle;
        PipeOutput(long handle) { this.handle=handle; }
        public void write(int b) throws IOException { write(new byte[]{(byte)b},0,1); }
        public synchronized void write(byte[] b,int o,int n) throws IOException { java.util.Objects.checkFromIndexSize(o,n,b.length);if(handle==0)throw new IOException("Pipe closed");try{writeNative(handle,b,o,n);}catch(RuntimeException e){throw new IOException(e);} }
        public synchronized void close() { if(handle!=0){closeNative(handle);handle=0;} }
    }
    private static final class WorkerProcess extends Process {
        private long process,job;
        private final PipeInput stdout,stderr;
        private final PipeOutput stdin;
        private volatile boolean destroyed;
        private Integer exited;
        private final java.util.Set<WorkerProcess> owner;
        WorkerProcess(long[] h,java.util.Set<WorkerProcess> owner) { process=h[0];job=h[1];stdin=new PipeOutput(h[2]);stdout=new PipeInput(h[3]);stderr=new PipeInput(h[4]);this.owner=owner; }
        public OutputStream getOutputStream(){return stdin;}
        public InputStream getInputStream(){return stdout;}
        public InputStream getErrorStream(){return stderr;}
        private synchronized int poll(long milliseconds){if(exited!=null)return exited;int code=waitNative(process,milliseconds);if(code!=-1){exited=code;closeNative(process);process=0;closeNative(job);job=0;}return code;}
        public int waitFor() throws InterruptedException { while(true){if(Thread.interrupted())throw new InterruptedException();int code=poll(100);if(code!=-1)return code;} }
        public boolean waitFor(long timeout,TimeUnit unit) throws InterruptedException { long end=System.nanoTime()+unit.toNanos(timeout);do{if(Thread.interrupted())throw new InterruptedException();if(poll(Math.min(100,Math.max(0,unit.toMillis(timeout))))!=-1)return true;}while(System.nanoTime()<end);return !isAlive(); }
        public int exitValue(){int code=poll(0);if(code==-1)throw new IllegalThreadStateException();return code;}
        public boolean isAlive(){return poll(0)==-1;}
        public synchronized void destroy(){if(!destroyed){destroyed=true;if(process!=0){terminateNative(process);poll(1000);if(process!=0){closeNative(process);process=0;exited=1;}}if(job!=0){closeNative(job);job=0;}stdin.close();stdout.close();stderr.close();owner.remove(this);}}
        public Process destroyForcibly(){destroy();return this;}
    }
}
