package ai.enterprise.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Embedded distribution materials. No extraction, temporary paths or persistent state. */
public final class LegalResources {
    private LegalResources() {}
    private static InputStream open(String name) throws IOException {
        InputStream input=LegalResources.class.getResourceAsStream("/enterpriseai/legal/"+name);
        if(input==null) throw new IOException("В этой сборке отсутствуют встроенные лицензионные материалы: "+name);
        return input;
    }
    public static String notices() throws IOException {
        try(var input=open("notices.txt")) { return new String(input.readAllBytes(),StandardCharsets.UTF_8); }
    }
    public static String inventory() throws IOException {
        try(var input=open("catalog.json")) { return new String(input.readAllBytes(),StandardCharsets.UTF_8); }
    }
    public static String changes() throws IOException {
        try(var input=open("changes.txt")) { return new String(input.readAllBytes(),StandardCharsets.UTF_8); }
    }
    public static long streamSources(OutputStream output) throws IOException {
        try(var input=open("corresponding-source.zip")) { return input.transferTo(output); }
    }
    /** Runs before SWT, the platform adapter, services and data-folder discovery. */
    public static boolean dispatch(String[] args,OutputStream output) throws IOException {
        if(args.length!=1) return false;
        switch(args[0]) {
            case "--licenses" -> output.write(notices().getBytes(StandardCharsets.UTF_8));
            case "--component-inventory" -> output.write(inventory().getBytes(StandardCharsets.UTF_8));
            case "--third-party-source-stdout" -> streamSources(output);
            default -> { return false; }
        }
        output.flush();
        return true;
    }
}
