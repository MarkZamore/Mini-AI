package ai.enterprise.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

/** Read-only path and source-version checks shared by document services. */
public final class VerifiedPaths {
    private VerifiedPaths() {}

    public static Path within(Path storedRoot, String storedPath, Path allowedRoot) throws IOException {
        if (storedPath == null || storedPath.isBlank() || storedPath.indexOf('\0') >= 0) {
            throw new IOException("Не указан путь источника.");
        }
        Path relative;
        try {
            relative = Path.of(storedPath);
        } catch (RuntimeException error) {
            throw new IOException("Некорректный путь источника.", error);
        }
        if (relative.isAbsolute()) {
            throw new IOException("База содержит абсолютный путь: требуется переносимый относительный путь.");
        }
        // toRealPath follows junctions and symbolic links before the containment check.
        Path allowed = allowedRoot.toRealPath();
        Path actual = storedRoot.resolve(relative).normalize().toRealPath();
        if (!actual.startsWith(allowed) || !Files.isRegularFile(actual)) {
            throw new IOException("Путь источника находится вне разрешённой папки или не является файлом.");
        }
        return actual;
    }

    public static String sha256(Path path, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 недоступен.", error);
        }
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream source = Files.newInputStream(path, StandardOpenOption.READ)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Проверка источника отменена.");
                }
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static Path verify(Path storedRoot, String storedPath, Path allowedRoot,
                              String expectedSha256, BooleanSupplier cancelled) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Некорректная SHA-256 версия источника.");
        }
        Path path = within(storedRoot, storedPath, allowedRoot);
        if (!sha256(path, cancelled).equals(expectedSha256)) {
            throw new IOException("Файл изменён: привязка относится к другой SHA-256 версии.");
        }
        // Detect a path or junction replacement during the read. A race after this
        // check remains possible when an external program opens a path itself.
        if (!within(storedRoot, storedPath, allowedRoot).equals(path)) {
            throw new IOException("Путь источника изменился во время проверки.");
        }
        return path;
    }
}
