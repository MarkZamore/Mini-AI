import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Base64;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.LogHandler;

/** Build-only acceptance probe. Reads one caller-supplied file; never writes files. */
public final class StaticRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected one read-only input path and optional probe mode");
        // The platform-JNI gate checks Windows UTF-16 argv separately. Here test NIO itself.
        String path = args[0].startsWith("base64:")
            ? new String(Base64.getDecoder().decode(args[0].substring(7)), StandardCharsets.UTF_8) : args[0];
        byte[] content = Files.readAllBytes(Path.of(path));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var result = executor.submit(() -> {
                byte[] memory = new byte[16 * 1024 * 1024];
                long sum = 0;
                for (int i = 0; i < memory.length; i += 4096) {
                    memory[i] = (byte) (i >>> 12);
                    sum += Byte.toUnsignedInt(memory[i]);
                }
                return sum;
            }).get();
            byte[] output = ("STATIC_RUNTIME_OK SHA256=" + digest + " SUM=" + result
                + " UNICODE=Кириллица Қазақша 🧠\n").getBytes(StandardCharsets.UTF_8);
            System.out.write(output);
            System.out.flush();
        }
        if (args.length == 2) switch (args[1]) {
            case "--hold" -> System.in.read();
            case "--fatal" -> ImageSingletons.lookup(LogHandler.class).fatalError();
            case "--oom" -> {
                var memory = new ArrayList<byte[]>();
                while (true) memory.add(new byte[16 * 1024 * 1024]);
            }
            default -> throw new IllegalArgumentException("Unknown probe mode");
        }
    }
}
