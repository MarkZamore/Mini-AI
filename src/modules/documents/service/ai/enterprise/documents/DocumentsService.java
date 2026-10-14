package ai.enterprise.documents;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Read-only access to the existing index and canonical documents. */
public final class DocumentsService implements Api.DocumentAccessServiceV1 {
    public static final int MAX_CANONICAL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ARTIFACT_CHAIN = 1024;
    private final Api.Context context;
    private final Api.DatabaseServiceV1 database;

    public DocumentsService(Api.Context context) {
        this.context = context;
        database = context.require(Api.DatabaseServiceV1.class);
    }

    public CompletionStage<Api.Result<List<Api.DocumentSummary>>> catalog(Api.CancellationToken cancellation) {
        return context.async(cancellation, () -> database.query("SELECT * FROM documents ORDER BY document_id", List.of(), cancellation)
                .stream().map(DocumentsService::summary).toList());
    }

    public CompletionStage<Api.Result<Api.DocumentSummary>> document(String documentId, Api.CancellationToken cancellation) {
        return context.async(cancellation, () -> documentSync(documentId, cancellation));
    }

    public CompletionStage<Api.Result<Map<String,Object>>> canonical(String documentId, Api.CancellationToken cancellation) {
        return context.async(cancellation, () -> {
            Api.DocumentSummary document = documentSync(documentId, cancellation);
            Path path = pathWithin(context.environment().db(), document.jsonPath(), context.environment().db());
            Map<String,Object> canonical;
            try {
                canonical = Json.object(readUtf8(path, cancellation));
            } catch (IllegalArgumentException error) {
                throw new Api.ServiceException("CANONICAL_INVALID", "Некорректный канонический JSON документа.", error);
            }
            if (!"1.0".equals(canonical.get("schema_version"))) {
                throw new Api.ServiceException("SCHEMA_INCOMPATIBLE", "Поддерживается схема документа версии 1.0.");
            }
            if (!documentId.equals(canonical.get("document_id")) || !document.sourceSha256().equals(canonical.get("source_version_sha256"))) {
                throw new Api.ServiceException("CANONICAL_VERSION_MISMATCH", "Версия канонического JSON не соответствует существующему индексу.");
            }
            Map<String,Object> source = Json.map(canonical.get("source"));
            if (!document.sourceSha256().equals(source.get("sha256")) || !document.sourcePath().equals(source.get("path"))) {
                throw new Api.ServiceException("CANONICAL_SOURCE_MISMATCH", "Оригинал в каноническом JSON не соответствует существующему индексу.");
            }
            cancellation.check();
            return immutable(canonical);
        });
    }

    public CompletionStage<Api.Result<Api.SourceResolution>> resolveSource(Api.SourceRef ref, Api.CancellationToken cancellation) {
        return context.async(cancellation, () -> resolveSync(ref, cancellation));
    }

    private Api.DocumentSummary documentSync(String id, Api.CancellationToken cancellation) {
        if (id == null || id.isBlank()) throw new Api.ServiceException("DOCUMENT_UNKNOWN", "Не указан идентификатор документа.");
        List<Map<String,Object>> rows = database.query("SELECT * FROM documents WHERE document_id=?", List.of(id), cancellation);
        if (rows.isEmpty()) throw new Api.ServiceException("DOCUMENT_UNKNOWN", "Документ не найден: " + id);
        return summary(rows.getFirst());
    }

    private Api.SourceResolution resolveSync(Api.SourceRef ref, Api.CancellationToken cancellation) {
        validateLocator(ref);
        List<Map<String,Object>> rows = database.query("SELECT * FROM documents WHERE source_sha256=? LIMIT 1", List.of(ref.sourceSha256()), cancellation);
        if (rows.isEmpty()) throw new Api.ServiceException("SOURCE_VERSION_UNKNOWN", "Неизвестная SHA-256 версия источника.");
        Api.DocumentSummary document = summary(rows.getFirst());
        Path original = verify(context.environment().db(), document.sourcePath(), context.environment().docs(), ref.sourceSha256(), cancellation);
        Path selected = original;
        List<Api.DerivationStep> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String artifactId = ref.artifactId();
        while (!"original".equals(artifactId)) {
            cancellation.check();
            if (!visited.add(artifactId)) throw new Api.ServiceException("ARTIFACT_CYCLE", "Обнаружен цикл происхождения артефактов.");
            if (chain.size() >= MAX_ARTIFACT_CHAIN) throw new Api.ServiceException("ARTIFACT_CHAIN_LIMIT", "Цепочка происхождения превышает безопасный предел.");
            List<Map<String,Object>> artifacts = database.query("SELECT * FROM artifacts WHERE document_id=? AND artifact_id=?", List.of(document.documentId(), artifactId), cancellation);
            if (artifacts.isEmpty()) throw new Api.ServiceException("ARTIFACT_UNKNOWN", "Неизвестный артефакт источника: " + artifactId);
            Map<String,Object> artifact = artifacts.getFirst();
            String sha = Json.string(artifact.get("sha256"));
            Path path = verify(context.environment().db(), Json.string(artifact.get("path")), context.environment().db(), sha, cancellation);
            String parent = Json.string(artifact.get("derived_from"));
            if (parent.isBlank()) throw new Api.ServiceException("ARTIFACT_PARENT_UNKNOWN", "Не указан исходный артефакт.");
            if (chain.isEmpty()) selected = path;
            chain.add(new Api.DerivationStep(document.documentId(), artifactId, path, sha, parent));
            artifactId = parent;
        }
        cancellation.check();
        return new Api.SourceResolution(document.documentId(), original, selected, ref.sourceSha256(), ref, List.copyOf(chain), true);
    }

    public static Api.DocumentSummary summary(Map<String,Object> row) {
        return new Api.DocumentSummary(Json.string(row.get("document_id")), Json.string(row.get("title")), Json.string(row.get("source_sha256")),
                Json.string(row.get("source_path")), Json.string(row.get("json_path")), Json.string(row.get("status")),
                strings(row.get("sections")), maps(row.get("warnings")));
    }

    private static Object decoded(Object value) { return value instanceof String text ? Json.parse(text) : value; }
    private static List<String> strings(Object value) {
        Object decoded = decoded(value);
        if (!(decoded instanceof List<?> list)) throw new Api.ServiceException("DATABASE_INVALID", "Некорректный список разделов документа.");
        return list.stream().map(Json::string).toList();
    }
    private static List<Map<String,Object>> maps(Object value) {
        Object decoded = decoded(value);
        if (!(decoded instanceof List<?> list)) throw new Api.ServiceException("DATABASE_INVALID", "Некорректный список предупреждений документа.");
        return list.stream().map(Json::map).map(DocumentsService::immutable).toList();
    }

    private static Path pathWithin(Path storedRoot, String storedPath, Path allowedRoot) {
        try { return VerifiedPaths.within(storedRoot, storedPath, allowedRoot); }
        catch (IOException error) { throw new Api.ServiceException("SOURCE_PATH_INVALID", "Источник недоступен или находится вне разрешённой папки: " + error.getMessage(), error); }
    }
    public static Path verify(Path storedRoot, String storedPath, Path allowedRoot, String expectedSha, Api.CancellationToken cancellation) {
        try { return VerifiedPaths.verify(storedRoot, storedPath, allowedRoot, expectedSha, cancellation::cancelled); }
        catch (java.util.concurrent.CancellationException error) { throw new Api.ServiceException("CANCELLED", "Проверка источника отменена.", error); }
        catch (IOException error) { throw new Api.ServiceException("SOURCE_VERIFICATION_FAILED", error.getMessage(), error); }
    }

    private static String readUtf8(Path path, Api.CancellationToken cancellation) throws IOException {
        if (Files.size(path) > MAX_CANONICAL_BYTES) throw new Api.ServiceException("CANONICAL_TOO_LARGE", "Канонический JSON превышает предел 32 МиБ.");
        ByteArrayOutputStream memory = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        try (InputStream stream = Files.newInputStream(path, StandardOpenOption.READ)) {
            int count;
            while ((count = stream.read(chunk)) != -1) {
                cancellation.check();
                if (count > MAX_CANONICAL_BYTES - memory.size()) throw new Api.ServiceException("CANONICAL_TOO_LARGE", "Канонический JSON превышает предел 32 МиБ.");
                memory.write(chunk, 0, count);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(memory.toByteArray())).toString();
        } catch (CharacterCodingException error) {
            throw new Api.ServiceException("CANONICAL_ENCODING", "Канонический JSON не является корректным UTF-8.", error);
        }
    }

    /** Preserves every locator field, including character offsets in code points. */
    static void validateLocator(Api.SourceRef ref) {
        if (ref == null || !ref.sourceSha256().matches("[0-9a-f]{64}") || ref.artifactId().isBlank()) {
            throw new Api.ServiceException("LOCATOR_INVALID", "Не указаны артефакт и SHA-256 версия привязки.");
        }
        Map<String,Object> fields = ref.fields();
        switch (ref.kind()) {
            case "pdf_region" -> { positiveInteger(fields, "page"); numbers(fields, "bbox", 4); numbers(fields, "page_size", 2); }
            case "ooxml_node" -> { requiredString(fields, "part"); requiredString(fields, "xpath"); }
            case "sheet_range" -> { requiredString(fields, "sheet"); requiredString(fields, "range"); }
            case "drawing" -> { requiredString(fields, "part"); requiredString(fields, "xpath"); if (!(fields.get("anchor") instanceof Map)) invalid("anchor"); }
            case "artifact" -> {}
            default -> throw new Api.ServiceException("LOCATOR_INVALID", "Неизвестный вид привязки: " + ref.kind());
        }
        for (String offset : List.of("char_start", "char_end")) if (fields.containsKey(offset)) nonNegativeInteger(fields, offset);
        if (fields.containsKey("char_start") && fields.containsKey("char_end") && Json.integer(fields.get("char_end")) < Json.integer(fields.get("char_start"))) invalid("char_end");
    }
    private static void requiredString(Map<String,Object> fields, String key) { if (!(fields.get(key) instanceof String text) || text.isEmpty()) invalid(key); }
    private static void positiveInteger(Map<String,Object> fields, String key) { nonNegativeInteger(fields, key); if (Json.integer(fields.get(key)) < 1) invalid(key); }
    private static void nonNegativeInteger(Map<String,Object> fields, String key) {
        if (!(fields.get(key) instanceof Number number) || number.doubleValue() < 0 || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.longValue()) invalid(key);
    }
    private static void numbers(Map<String,Object> fields, String key, int count) {
        if (!(fields.get(key) instanceof List<?> list) || list.size() != count) invalid(key);
        for (Object item : (List<?>)fields.get(key)) if (!(item instanceof Number number) || !Double.isFinite(number.doubleValue())) invalid(key);
    }
    private static void invalid(String key) { throw new Api.ServiceException("LOCATOR_INVALID", "Некорректное поле привязки: " + key); }

    @SuppressWarnings("unchecked") private static <T> T frozen(T value) {
        if (value instanceof Map<?,?> map) { var copy = new LinkedHashMap<String,Object>(); map.forEach((key, item) -> copy.put(key.toString(), frozen(item))); return (T)Collections.unmodifiableMap(copy); }
        if (value instanceof List<?> list) return (T)Collections.unmodifiableList(list.stream().map(DocumentsService::frozen).toList());
        return value;
    }
    private static Map<String,Object> immutable(Map<String,Object> map) { return frozen(map); }
}
