package ai.enterprise.documents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Safety checks use fixtures only under src/build, never the real corpus. */
public final class VerifiedPathsTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "src/build/tests/documents-paths" : args[0])
                .toAbsolutePath().normalize().resolve(UUID.randomUUID().toString());
        Path docs = Files.createDirectories(root.resolve("docs"));
        Path db = Files.createDirectories(root.resolve("db"));
        Path original = docs.resolve("Казахский ә ғ ң файл.txt");
        byte[] data = "Источник: қазақша.\n😀 Unicode positions remain in locator.\n".getBytes(StandardCharsets.UTF_8);
        Files.write(original, data);
        String relative = "../docs/" + original.getFileName();
        String sha = VerifiedPaths.sha256(original, () -> false);
        check(VerifiedPaths.verify(db, relative, docs, sha, () -> false).equals(original.toRealPath()),
                "Valid Unicode path was rejected");

        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        rejected(() -> VerifiedPaths.within(db, "../outside.txt", docs), "Traversal escape");
        rejected(() -> VerifiedPaths.within(db, original.toString(), docs), "Absolute stored path");
        rejected(() -> VerifiedPaths.within(db, "../docs/missing.txt", docs), "Missing file");
        rejected(() -> VerifiedPaths.verify(db, relative, docs, "0".repeat(64), () -> false), "Changed SHA");
        rejected(() -> VerifiedPaths.verify(db, relative, docs, "bad", () -> false), "Malformed SHA");
        rejected(() -> VerifiedPaths.sha256(original, () -> true), "Cancellation");
        check(java.util.Arrays.equals(data, Files.readAllBytes(original)), "Verification modified original");

        Path longDirectory = docs;
        for (int i = 0; i < 4; i++) longDirectory = longDirectory.resolve("длинная папка " + "я".repeat(72));
        Files.createDirectories(longDirectory);
        Path longOriginal = longDirectory.resolve("документ с пробелами ә.txt");
        Files.write(longOriginal, data);
        String longStoredPath = "../docs/" + docs.relativize(longOriginal).toString();
        check(longOriginal.toString().length() > 260, "Long path fixture must exceed MAX_PATH");
        check(VerifiedPaths.verify(db, longStoredPath, docs, sha, () -> false).equals(longOriginal.toRealPath()),
                "Long source path verification failed");

        // Windows may require Developer Mode for symbolic links. If available,
        // following the link must not permit traversal outside the docs root.
        try {
            Files.createSymbolicLink(docs.resolve("escape.txt"), outside);
            rejected(() -> VerifiedPaths.within(db, "../docs/escape.txt", docs), "Symlink escape");
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            System.out.println("Symbolic-link fixture unavailable; junction/symlink check remains in Windows acceptance matrix.");
        }
        System.out.println("VerifiedPathsTest passed.");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static void rejected(Throwing action, String message) throws Exception {
        try { action.run(); } catch (java.io.IOException | java.util.concurrent.CancellationException expected) { return; }
        throw new AssertionError(message + " was accepted");
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
