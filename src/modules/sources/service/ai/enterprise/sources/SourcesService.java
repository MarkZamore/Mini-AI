package ai.enterprise.sources;

import ai.enterprise.contracts.Api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletionStage;

/** Opens verified originals using the platform port, without depending on a documents implementation. */
public final class SourcesService implements Api.SourceViewerServiceV1 {
    private final Api.Context context;
    private final Api.DocumentAccessServiceV1 documents;
    public SourcesService(Api.Context context) {
        this.context = context;
        documents = context.require(Api.DocumentAccessServiceV1.class);
    }

    public CompletionStage<Api.Result<Void>> openDocument(String id, Api.CancellationToken cancellation) {
        return documents.document(id, cancellation).thenCompose(result -> {
            if (!result.success()) return java.util.concurrent.CompletableFuture.completedFuture(Api.Result.fail(result.error().code(), result.error().message()));
            return context.async(cancellation, () -> {
                Api.DocumentSummary document = result.value();
                Path original = verifyOriginal(document.sourcePath(), document.sourceSha256(), cancellation);
                cancellation.check();
                context.environment().platform().openWith(original);
                return null;
            });
        });
    }

    public CompletionStage<Api.Result<Void>> openSource(Api.SourceRef ref, Api.CancellationToken cancellation) {
        return documents.resolveSource(ref, cancellation).thenCompose(result -> {
            if (!result.success()) return java.util.concurrent.CompletableFuture.completedFuture(Api.Result.fail(result.error().code(), result.error().message()));
            return context.async(cancellation, () -> {
                Api.SourceResolution resolution = result.value();
                if (!resolution.verified()) throw new Api.ServiceException("SOURCE_UNVERIFIED", "Источник не прошёл проверку версии.");
                // Always open the original. The selected derived file only defines
                // coordinates; Open With cannot reliably navigate those locators.
                Path original = resolution.originalPath().toRealPath();
                Path docsRoot = context.environment().docs().toRealPath();
                if (!original.startsWith(docsRoot) || !Files.isRegularFile(original)) throw new Api.ServiceException("SOURCE_PATH_INVALID", "Оригинал находится вне папки docs.");
                verifyDigest(original, resolution.sourceSha256(), cancellation);
                cancellation.check();
                context.environment().platform().openWith(original);
                return null;
            });
        });
    }

    private Path verifyOriginal(String relativePath, String sha, Api.CancellationToken cancellation) throws Exception {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) throw new Api.ServiceException("SOURCE_PATH_INVALID", "Абсолютные пути источников не поддерживаются.");
        Path docsRoot = context.environment().docs().toRealPath();
        Path original = context.environment().db().resolve(relative).normalize().toRealPath();
        if (!original.startsWith(docsRoot) || !Files.isRegularFile(original)) throw new Api.ServiceException("SOURCE_PATH_INVALID", "Оригинал находится вне папки docs или не является файлом.");
        verifyDigest(original, sha, cancellation);
        if (!context.environment().db().resolve(relative).normalize().toRealPath().equals(original)) throw new Api.ServiceException("SOURCE_PATH_CHANGED", "Путь оригинала изменился во время проверки.");
        return original;
    }

    private static void verifyDigest(Path original, String sha, Api.CancellationToken cancellation) throws Exception {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new Api.ServiceException("SOURCE_VERSION_INVALID", "Некорректная SHA-256 версия оригинала.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream stream = Files.newInputStream(original, StandardOpenOption.READ)) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                cancellation.check();
                if (Thread.currentThread().isInterrupted()) throw new Api.ServiceException("CANCELLED", "Проверка источника отменена.");
                if (count > 0) digest.update(buffer, 0, count);
            }
        } catch (IOException error) { throw new Api.ServiceException("SOURCE_UNAVAILABLE", "Оригинал недоступен: " + error.getMessage(), error); }
        if (!HexFormat.of().formatHex(digest.digest()).equals(sha)) throw new Api.ServiceException("SOURCE_VERSION_CHANGED", "Оригинал изменён: привязка относится к прежней версии.");
    }
}
