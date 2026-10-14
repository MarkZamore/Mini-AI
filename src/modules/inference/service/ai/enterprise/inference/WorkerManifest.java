package ai.enterprise.inference;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only validation of the independently prepared, self-contained model package. */
public record WorkerManifest(Path executable, Path weights, String engine, String revision,
                             String precision, int contextTokens, int threads,
                             long minFreeMemoryBytes, long maxProcessMemoryBytes) {
    private static final Set<String> KEYS = Set.of("schemaVersion", "protocol", "executable",
        "engine", "revision", "architecture", "supportedIsa", "weights", "files", "precision",
        "contextTokens", "threads", "minFreeMemoryBytes", "maxProcessMemoryBytes");

    public static WorkerManifest verify(Map<String, Object> value, Path modelRoot,
                                        Set<String> availableIsa, long availableMemory,
                                        BooleanSupplier cancelled) throws Exception {
        if (!value.keySet().equals(KEYS)) throw new IllegalArgumentException("worker.json: неверный набор полей");
        if (integer(value, "schemaVersion", 1, 1) != 1
            || !"enterpriseai.inference.v1".equals(string(value, "protocol"))
            || !"windows-x64".equals(string(value, "architecture"))
            || !"inference-worker.exe".equals(string(value, "executable"))) {
            throw new IllegalArgumentException("Несовместимый протокол или архитектура модели");
        }
        Path root = modelRoot.toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("model не является папкой");
        Set<String> requirements = new HashSet<>();
        for (Object item : array(value, "supportedIsa")) {
            if (!(item instanceof String isa) || !isa.matches("[a-z0-9][a-z0-9_.-]{0,63}") || !requirements.add(isa))
                throw new IllegalArgumentException("worker.json: неверный supportedIsa");
            if (!availableIsa.contains(isa))
                throw new IllegalArgumentException("Процессор не поддерживает требуемый набор инструкций: " + isa);
        }
        if (requirements.isEmpty() || requirements.size() > 32) throw new IllegalArgumentException("worker.json: неверный supportedIsa");
        int context = (int) integer(value, "contextTokens", 1, 1_048_576);
        int threads = (int) integer(value, "threads", 1, 256);
        long min = integer(value, "minFreeMemoryBytes", 1, 9_007_199_254_740_991L);
        long max = integer(value, "maxProcessMemoryBytes", 1, 9_007_199_254_740_991L);
        if (max >= min) throw new IllegalArgumentException("worker.json: необходим резерв свободной памяти сверх лимита worker");
        if (availableMemory < min || availableMemory < max)
            throw new InsufficientMemoryException(Math.max(min, max), availableMemory);
        FileSpec weights = spec(object(value, "weights"));
        Map<String, FileSpec> inventory = new LinkedHashMap<>();
        Map<Path, FileSpec> resolved = new LinkedHashMap<>();
        if (array(value, "files").size() > 1024) throw new IllegalArgumentException("worker.json: слишком много файлов");
        for (Object item : array(value, "files")) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("worker.json: неверный files");
            @SuppressWarnings("unchecked") FileSpec entry = spec((Map<String,Object>) map);
            if (inventory.putIfAbsent(entry.path().toLowerCase(Locale.ROOT), entry) != null)
                throw new IllegalArgumentException("worker.json: повторный путь");
            Path path = contained(root, entry.path());
            if (!Files.isRegularFile(path) || resolved.putIfAbsent(path, entry) != null)
                throw new IllegalArgumentException("worker.json: отсутствующий файл или повторная цель");
        }
        FileSpec exe = inventory.get("inference-worker.exe");
        if (exe == null || !weights.equals(inventory.get(weights.path().toLowerCase(Locale.ROOT))))
            throw new IllegalArgumentException("worker.json: EXE и веса должны входить в files");
        // No library extraction or replaceable DLL backends: only one standalone EXE.
        try (var paths = Files.walk(root, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
            var iterator = paths.iterator();
            int visited = 0;
            while (iterator.hasNext()) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                if (++visited > 16384) throw new IllegalArgumentException("Слишком много элементов в model");
                Path path = iterator.next();
                if (!path.equals(root)) validateRelative(root.relativize(path).toString().replace('\\', '/'));
                if (!path.toRealPath().startsWith(root)) throw new IllegalArgumentException("Ссылка указывает вне model");
                if (!Files.isRegularFile(path)) continue;
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".dll") || (name.endsWith(".exe")
                    && !path.toRealPath().equals(contained(root, exe.path()))))
                    throw new ai.enterprise.contracts.Api.ServiceException("MODEL_PACKAGE_NOT_SELF_CONTAINED",
                        "Комплект модели содержит дополнительный EXE или DLL: " + root.relativize(path));
                if ((name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".gguf"))
                    && !resolved.containsKey(path.toRealPath()))
                    throw new IllegalArgumentException("Непроверенный исполняемый файл или веса: " + root.relativize(path));
            }
        }
        byte[] buffer = new byte[1 << 20];
        for (var entry : resolved.entrySet()) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(entry.getKey())) {
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    digest.update(buffer, 0, n);
                }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(entry.getValue().sha256()))
                throw new IllegalArgumentException("SHA-256 не совпадает: " + entry.getValue().path());
        }
        Arrays.fill(buffer, (byte) 0);
        WorkerPeAudit.verify(contained(root, exe.path()), cancelled);
        return new WorkerManifest(contained(root, exe.path()), contained(root, weights.path()),
            boundedString(value, "engine", 256), boundedString(value, "revision", 512), boundedString(value, "precision", 128),
            context, threads, min, max);
    }

    private record FileSpec(String path, String sha256) {}
    private static FileSpec spec(Map<String,Object> value) {
        if (!value.keySet().equals(Set.of("path", "sha256"))) throw new IllegalArgumentException("Неверное описание файла");
        String path = string(value, "path"), hash = string(value, "sha256");
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Неверный SHA-256");
        validateRelative(path);
        return new FileSpec(path, hash);
    }
    private static void validateRelative(String path) {
        if (path.length() > 32760 || path.matches(".*[\\\\:\\x00-\\x1f<>\"|?*].*") || path.startsWith("/"))
            throw new IllegalArgumentException("Требуется относительный путь с разделителем /");
        for (String part : path.split("/", -1)) {
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(" ") || part.endsWith(".")
                || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
                throw new IllegalArgumentException("Небезопасный относительный путь");
        }
    }
    private static Path contained(Path root, String name) throws Exception {
        validateRelative(name);
        Path lexical = root.resolve(name).normalize();
        if (!lexical.startsWith(root)) throw new IllegalArgumentException("Путь вне model");
        Path real = lexical.toRealPath();
        if (!real.startsWith(root)) throw new IllegalArgumentException("Ссылка указывает вне model");
        return real;
    }
    private static String string(Map<String,Object> value, String key) {
        if (!(value.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Неверное поле " + key);
        return s;
    }
    private static String boundedString(Map<String,Object> value, String key, int maximum) {
        String result = string(value, key);
        if (result.length() > maximum) throw new IllegalArgumentException("Слишком длинное поле " + key);
        return result;
    }
    private static long integer(Map<String,Object> value, String key, long min, long max) {
        Object raw = value.get(key);
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("Неверное поле " + key);
        long result;
        try { result = new java.math.BigDecimal(n.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Неверное целое " + key); }
        if (result < min || result > max) throw new IllegalArgumentException("Поле вне допустимых пределов: " + key);
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> value, String key) {
        if (!(value.get(key) instanceof Map<?,?> map)) throw new IllegalArgumentException("Неверное поле " + key);
        return (Map<String,Object>) map;
    }
    private static List<?> array(Map<String,Object> value, String key) {
        if (!(value.get(key) instanceof List<?> list)) throw new IllegalArgumentException("Неверное поле " + key);
        return list;
    }
    public static final class InsufficientMemoryException extends IllegalArgumentException {
        public InsufficientMemoryException(long required, long available) {
            super(String.format(Locale.ROOT, "Недостаточно свободной памяти для модели: требуется %.1f ГиБ, доступно %.1f ГиБ", required / 1073741824.0, available / 1073741824.0));
        }
    }
}
