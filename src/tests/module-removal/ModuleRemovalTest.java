package ai.enterprise.tests;

import ai.enterprise.contracts.Json;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Builds every functional-module composition in an isolated output directory, then loads and
 * registers its generated catalog. Original module folders and all document data remain untouched.
 * Run with Java 25: ModuleRemovalTest [absolute src root] [semicolon-separated dependency jars].
 */
public final class ModuleRemovalTest {
    private static final List<String> EXPECTED_IDS = List.of("assistant", "database", "documents", "graph", "inference", "search", "sources");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern OPTIONAL_REFERENCE = Pattern.compile("\\bai\\.enterprise\\.(assistant|database|documents|graph|inference|search|sources)\\.");
    private static final Map<String, Set<String>> COMMANDS = Map.of(
            "database", Set.of("database-info"), "documents", Set.of("catalog", "document", "canonical", "resolve"),
            "search", Set.of("search", "get-fragment"), "graph", Set.of("graph", "neighbors"),
            "inference", Set.of("model-status", "model-generate"), "sources", Set.of("open", "open-source"), "assistant", Set.of());

    private final Path sourceRoot;
    private final Path workRoot;
    private final String reportName;
    private final List<Path> jars;
    private final JavaCompiler compiler;
    private final List<Module> originals;
    private final List<Path> coreSources;
    private final List<Map<String, Object>> results = new ArrayList<>();

    private record Module(String id, Path folder, Map<String, Object> manifest) {}

    private ModuleRemovalTest(Path root, List<Path> jars, String reportName) throws IOException {
        require(reportName.matches("[A-Za-z0-9_.-]+\\.json"), "Report must be a JSON basename");
        this.reportName = reportName;
        sourceRoot = root.toRealPath();
        workRoot = sourceRoot.resolve("build/module-removal");
        Files.createDirectories(workRoot);
        compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "Run the test with a JDK, not an application runtime");
        originals = discover(sourceRoot.resolve("modules"));
        require(originals.stream().map(Module::id).sorted().toList().equals(EXPECTED_IDS),
                "Expected the seven approved functional modules before running the matrix");
        coreSources = new ArrayList<>();
        for (String core : List.of("contracts", "bootstrap", "shell", "platform-windows"))
            coreSources.addAll(javaFiles(sourceRoot.resolve("core").resolve(core)));
        checkArchitecture();
        for (Path jar : jars) require(Files.isRegularFile(jar), "Missing compilation dependency: " + jar);
        this.jars = snapshotDependencies(jars);
    }

    public static void main(String[] args) throws Exception {
        Path src = args.length == 0 ? Path.of("src").toAbsolutePath() : Path.of(args[0]).toAbsolutePath();
        if (!Files.isDirectory(src.resolve("modules")) && Files.isDirectory(src.resolve("../modules")))
            src = src.resolve("..").normalize();
        List<Path> jars;
        if (args.length > 1) {
            jars = java.util.Arrays.stream(args[1].split(Pattern.quote(java.io.File.pathSeparator)))
                    .filter(s -> !s.isBlank()).map(s -> Path.of(s).toAbsolutePath()).toList();
        } else jars = dependencyJars(src);
        ModuleRemovalTest test = new ModuleRemovalTest(src, jars, args.length > 2 ? args[2] : "module-removal.json");
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            for (int mask = 0; mask < (1 << test.originals.size()); mask++) test.matrixCase(mask);
            test.realDeletionCases();
        } catch (Throwable error) { failure = error; }
        finally { test.writeReport(started, failure); }
        if (failure != null) {
            if (failure instanceof Exception error) throw error;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        }
        System.out.println("ModuleRemovalTest: PASS, all 128 Java compilations and two copied-folder deletion scenarios");
    }

    private static List<Path> dependencyJars(Path src) throws IOException {
        var paths = new LinkedHashSet<Path>();
        Path patched = src.resolve("build/dependencies/swt-3.135.0-patched.jar");
        require(Files.isRegularFile(patched), "Build the patched SWT jar first");
        paths.add(patched.toAbsolutePath());
        Path runtime = src.resolve("build/runtime-libs");
        if (Files.isDirectory(runtime)) try (var files = Files.list(runtime)) {
            files.filter(p -> p.toString().endsWith(".jar") && !p.getFileName().toString().contains("swt-"))
                    .sorted().map(Path::toAbsolutePath).forEach(paths::add);
        }
        require(paths.stream().anyMatch(p -> p.getFileName().toString().startsWith("nativeimage-")),
                "Run Gradle copyRuntime so the pinned GraalVM nativeimage API is available");
        return List.copyOf(paths);
    }

    /** Build jobs may regenerate jars concurrently; a complete matrix uses one verified snapshot. */
    private List<Path> snapshotDependencies(List<Path> dependencies) throws IOException {
        Path folder = workRoot.resolve("dependencies");
        reset(folder);
        var result = new ArrayList<Path>();
        for (int i = 0; i < dependencies.size(); i++) {
            Path original = dependencies.get(i);
            byte[] before = digest(original);
            Path snapshot = folder.resolve(String.format(java.util.Locale.ROOT, "%02d-%s", i, original.getFileName()));
            Files.copy(original, snapshot, StandardCopyOption.REPLACE_EXISTING);
            require(java.util.Arrays.equals(before, digest(original)) && java.util.Arrays.equals(before, digest(snapshot)),
                    "Build dependency changed during snapshot; rerun after its build completes: " + original);
            try (var zip = new java.util.zip.ZipFile(snapshot.toFile())) {
                var entries = zip.entries();
                byte[] buffer = new byte[64 * 1024];
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    var checksum = new java.util.zip.CRC32();
                    try (var bytes = zip.getInputStream(entry)) {
                        int read;
                        while ((read = bytes.read(buffer)) != -1) checksum.update(buffer, 0, read);
                    }
                    require(checksum.getValue() == entry.getCrc(), "Damaged dependency entry: " + snapshot + " / " + entry.getName());
                }
            }
            result.add(snapshot);
        }
        return List.copyOf(result);
    }
    private static byte[] digest(Path file) throws IOException {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            try (var bytes = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = bytes.read(buffer)) != -1) hash.update(buffer, 0, read);
            }
            return hash.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void checkArchitecture() throws IOException {
        for (Path source : coreSources) require(!OPTIONAL_REFERENCE.matcher(Files.readString(source)).find(),
                "Core references a concrete optional implementation: " + source);
        for (Module module : originals) {
            for (Path source : javaFiles(module.folder.resolve("service"))) {
                String text = Files.readString(source);
                require(!text.contains("org.eclipse.swt"), "Business code depends on the interface toolkit: " + source);
                var references = OPTIONAL_REFERENCE.matcher(text);
                while (references.find()) require(references.group(1).equals(module.id),
                        "Cross-module implementation dependency: " + source + " → " + references.group(1));
            }
        }
    }

    private void matrixCase(int mask) throws Exception {
        Path folder = workRoot.resolve("mask-" + String.format(java.util.Locale.ROOT, "%03d", mask));
        reset(folder);
        Path manifests = folder.resolve("modules");
        Files.createDirectories(manifests);
        for (int i = 0; i < originals.size(); i++) if ((mask & (1 << i)) != 0) {
            Module module = originals.get(i);
            Path target = manifests.resolve(module.folder.getFileName());
            Files.createDirectories(target);
            Files.copy(module.folder.resolve("module.json"), target.resolve("module.json"));
        }
        List<Module> scanned = discover(manifests);
        Map<String, Module> originalById = new HashMap<>();
        for (Module module : originals) originalById.put(module.id, module);
        List<Module> composition = scanned.stream().map(m -> new Module(m.id,
                originalById.get(m.id).folder, m.manifest)).toList();
        long started = System.nanoTime();
        compileAndRegister(folder, composition);
        results.add(Map.of("mask", mask, "modules", scanned.stream().map(Module::id).toList(),
                "compile", true, "registration", true, "seconds", (System.nanoTime() - started) / 1_000_000_000.0));
        if (mask % 16 == 0 || mask == 127) System.out.println("Module matrix " + (mask + 1) + "/128 passed");
    }

    private void realDeletionCases() throws Exception {
        Path sandbox = workRoot.resolve("copied-folder-deletion");
        reset(sandbox);
        Path copiedModules = sandbox.resolve("modules");
        Files.createDirectories(copiedModules);
        for (Module module : originals) copyTree(module.folder, copiedModules.resolve(module.folder.getFileName()));
        // These are actual deletions in an independently copied source tree under src/build.
        safeDelete(copiedModules.resolve("assistant"));
        safeDelete(copiedModules.resolve("graph"));
        List<Module> noPanels = discover(copiedModules);
        compileAndRegister(sandbox.resolve("no-panels"), noPanels);
        require(noPanels.stream().noneMatch(m -> m.id.equals("assistant") || m.id.equals("graph")), "Deleted panes reappeared");
        results.add(Map.of("scenario", "copied folders assistant+graph deleted", "compile", true, "registration", true));
        for (Module module : noPanels) safeDelete(module.folder);
        List<Module> minimal = discover(copiedModules);
        require(minimal.isEmpty(), "Deleted copied module folders remain discoverable");
        compileAndRegister(sandbox.resolve("minimal"), minimal);
        results.add(Map.of("scenario", "all copied functional folders deleted", "compile", true, "registration", true));
    }

    private void compileAndRegister(Path folder, List<Module> composition) throws Exception {
        Files.createDirectories(folder);
        List<Module> ordered = order(composition);
        Path generated = folder.resolve("generated/ai/enterprise/generated/GeneratedCatalog.java");
        Files.createDirectories(generated.getParent());
        String serviceFactories = ordered.stream().map(m -> field(m, "serviceFactory", "factory"))
                .filter(s -> !s.isBlank()).map(s -> "new " + s + "()").collect(java.util.stream.Collectors.joining(","));
        String panelFactories = ordered.stream().map(m -> field(m, "uiFactory", ""))
                .filter(s -> !s.isBlank()).map(s -> "new " + s + "()").collect(java.util.stream.Collectors.joining(","));
        Files.writeString(generated, "package ai.enterprise.generated;\npublic final class GeneratedCatalog {\n"
                + "private GeneratedCatalog(){}\n"
                + "public static java.util.List<ai.enterprise.contracts.Api.ModuleFactory> modules(){return java.util.List.of(" + serviceFactories + ");}\n"
                + "public static java.util.List<ai.enterprise.shell.PanelFactory> panels(){return java.util.List.of(" + panelFactories + ");}\n}\n",
                StandardCharsets.UTF_8);
        var sourceFiles = new ArrayList<>(coreSources);
        for (Module module : ordered) {
            sourceFiles.addAll(javaFiles(module.folder.resolve("service")));
            sourceFiles.addAll(javaFiles(module.folder.resolve("ui")));
        }
        sourceFiles.add(generated);
        Path classes = folder.resolve("classes");
        Files.createDirectories(classes);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjectsFromPaths(sourceFiles);
            String classpath = jars.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            var options = List.of("--release", "25", "-encoding", "UTF-8", "-proc:none", "-classpath", classpath, "-d", classes.toString());
            StringWriter compilerOutput = new StringWriter();
            boolean success = compiler.getTask(compilerOutput, files, diagnostics, options, null, units).call();
            if (!success) {
                String report = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .map(Object::toString).collect(java.util.stream.Collectors.joining("\n"));
                Files.writeString(folder.resolve("compiler-errors.txt"), report + "\n" + compilerOutput, StandardCharsets.UTF_8);
                throw new AssertionError("Compilation failed for " + ordered.stream().map(Module::id).toList() + "\n" + report);
            }
        }
        // The parent intentionally cannot see application classes: removed factories cannot be
        // supplied by a previous full compilation or by the JVM that runs this test.
        var urls = new ArrayList<URL>();
        urls.add(classes.toUri().toURL());
        for (Path jar : jars) urls.add(jar.toUri().toURL());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> catalog = Class.forName("ai.enterprise.generated.GeneratedCatalog", true, loader);
            List<?> modules = (List<?>) catalog.getMethod("modules").invoke(null);
            List<?> panels = (List<?>) catalog.getMethod("panels").invoke(null);
            require(modules.size() == ordered.stream().filter(m -> !field(m, "serviceFactory", "factory").isBlank()).count(),
                    "Wrong generated service-factory count");
            require(panels.size() == ordered.stream().filter(m -> !field(m, "uiFactory", "").isBlank()).count(),
                    "Wrong generated pane count");
            Class<?> moduleSpi = loader.loadClass("ai.enterprise.contracts.Api$ModuleFactory");
            Class<?> panelSpi = loader.loadClass("ai.enterprise.shell.PanelFactory");
            Set<String> ids = new LinkedHashSet<>();
            for (Object module : modules) ids.add((String) moduleSpi.getMethod("id").invoke(module));
            require(ids.equals(ordered.stream().map(Module::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))),
                    "Generated service IDs differ from discovered modules");
            Set<String> paneIds = new HashSet<>();
            for (Object panel : panels) paneIds.add((String) panelSpi.getMethod("id").invoke(panel));
            require(paneIds.equals(ordered.stream().filter(m -> !field(m, "uiFactory", "").isBlank()).map(Module::id)
                    .collect(java.util.stream.Collectors.toSet())), "Generated panes differ from selected folders");
            for (Module missing : originals) if (!ids.contains(missing.id)) {
                String factory = field(missing, "serviceFactory", "factory");
                if (!factory.isBlank()) assertMissing(loader, factory);
                String pane = field(missing, "uiFactory", "");
                if (!pane.isBlank()) assertMissing(loader, pane);
            }
            register(loader, modules, panels, ids, moduleSpi, folder);
        }
    }

    private static void assertMissing(ClassLoader loader, String name) throws Exception {
        try { Class.forName(name, false, loader); throw new AssertionError("Removed implementation still exists: " + name); }
        catch (ClassNotFoundException expected) { }
    }

    private void register(ClassLoader loader, List<?> modules, List<?> panels, Set<String> selected,
                          Class<?> moduleSpi, Path folder) throws Exception {
        Class<?> platformType = loader.loadClass("ai.enterprise.contracts.Api$Platform");
        Object platform = Proxy.newProxyInstance(loader, new Class<?>[]{platformType}, (proxy, method, args) -> switch (method.getName()) {
            case "executablePath" -> folder.resolve("missing-input/EnterpriseAI.exe");
            case "availableMemoryBytes" -> 32L * 1024 * 1024 * 1024;
            case "cpuSupports" -> true;
            case "close" -> null;
            case "toString" -> "Module-removal fake platform";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("Registration unexpectedly invoked native/external operation: " + method.getName());
        });
        Class<?> environmentType = loader.loadClass("ai.enterprise.contracts.Api$Environment");
        Class<?> contextType = loader.loadClass("ai.enterprise.contracts.Api$Context");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Object environment = environmentType.getConstructor(Path.class, ExecutorService.class, platformType)
                .newInstance(folder.resolve("missing-input"), executor, platform);
        Object context = contextType.getConstructor(environmentType).newInstance(environment);
        try {
            for (Object module : modules) {
                try { moduleSpi.getMethod("register", contextType).invoke(module, context); }
                catch (InvocationTargetException error) { throw new AssertionError("Registration crashed with absent input/dependency: "
                        + moduleSpi.getMethod("id").invoke(module), error.getCause()); }
            }
            Set<?> actualCommands = (Set<?>) contextType.getMethod("commands").invoke(context);
            Set<String> expectedCommands = new HashSet<>();
            for (String id : selected) {
                if ((id.equals("documents") || id.equals("sources")) && !selected.contains("database")) continue;
                if (id.equals("sources") && !selected.contains("documents")) continue;
                expectedCommands.addAll(COMMANDS.get(id));
            }
            require(actualCommands.equals(expectedCommands), "Commands do not match composition " + selected
                    + ": expected " + expectedCommands + ", actual " + actualCommands);
            // Availability errors must remain ordinary DTO results when dependent data/modules are absent.
            if (selected.contains("graph")) {
                Class<?> graphType = loader.loadClass("ai.enterprise.contracts.Api$GraphServiceV1");
                Object found = ((java.util.Optional<?>) contextType.getMethod("find", Class.class).invoke(context, graphType)).orElseThrow();
                Object token = loader.loadClass("ai.enterprise.contracts.Api$CancellationToken").getConstructor().newInstance();
                Object future = graphType.getMethod("snapshot", token.getClass()).invoke(found, token);
                Object result = ((java.util.concurrent.CompletionStage<?>) future).toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
                require(!(boolean) result.getClass().getMethod("success").invoke(result), "Missing database unexpectedly produced a graph");
            }
        } finally { contextType.getMethod("close").invoke(context); executor.shutdownNow(); }
    }

    private static List<Module> discover(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        List<Module> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (var paths = Files.list(folder)) {
            for (Path path : paths.sorted().toList()) {
                Path manifest = path.resolve("module.json");
                if (!Files.isDirectory(path) || !Files.isRegularFile(manifest)) continue;
                Map<String, Object> data = Json.object(Files.readString(manifest, StandardCharsets.UTF_8));
                String id = Json.string(data.get("id"));
                require(id.matches("[a-z][a-z0-9-]*") && ids.add(id), "Invalid/duplicate module ID: " + id);
                require(Json.string(data.getOrDefault("contractVersion", "1.0")).startsWith("1."), "Incompatible contract " + id);
                result.add(new Module(id, path, data));
            }
        }
        return List.copyOf(result);
    }

    private static List<Module> order(List<Module> modules) {
        Map<String, String> providers = new HashMap<>();
        for (Module module : modules) {
            providers.put(module.id, module.id);
            for (String service : strings(module.manifest.get("provides")))
                require(providers.put(service, module.id) == null, "Duplicate provider " + service);
        }
        Map<String, Module> remaining = new HashMap<>();
        for (Module module : modules) remaining.put(module.id, module);
        List<Module> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<Module> ready = remaining.values().stream().filter(module -> strings(module.manifest.get("requires")).stream()
                            .noneMatch(dependency -> remaining.containsKey(providers.get(dependency))))
                    .sorted(Comparator.comparing(Module::id)).toList();
            require(!ready.isEmpty(), "Dependency cycle: " + remaining.keySet());
            for (Module module : ready) { remaining.remove(module.id); result.add(module); }
        }
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> values ? values.stream().map(Object::toString).toList() : List.of();
    }
    private static String field(Module module, String key, String fallback) {
        return Json.string(module.manifest.getOrDefault(key, module.manifest.get(fallback)));
    }
    private static List<Path> javaFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var paths = Files.walk(folder)) {
            return paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")
                    && !p.toString().contains(java.io.File.separator + "resources" + java.io.File.separator)
                    && !p.toString().contains(java.io.File.separator + "native" + java.io.File.separator)).sorted().toList();
        }
    }

    private void copyTree(Path original, Path target) throws IOException {
        require(original.toRealPath().startsWith(sourceRoot.resolve("modules")), "Unexpected copy source");
        require(target.toAbsolutePath().normalize().startsWith(workRoot), "Copy destination outside test build directory");
        try (var walk = Files.walk(original)) {
            for (Path path : walk.sorted().toList()) {
                require(!Files.isSymbolicLink(path), "Refuse to copy module symlink: " + path);
                Path destination = target.resolve(original.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private void reset(Path folder) throws IOException { if (Files.exists(folder)) safeDelete(folder); Files.createDirectories(folder); }
    private void safeDelete(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        require(!normalized.equals(workRoot) && normalized.startsWith(workRoot), "Unsafe test deletion: " + normalized);
        if (!Files.exists(normalized)) return;
        require(normalized.toRealPath().startsWith(workRoot.toRealPath()), "Test deletion resolves outside build workspace");
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private void writeReport(long started, Throwable failure) throws IOException {
        Path report = sourceRoot.resolve("build/test-results").resolve(reportName);
        Files.createDirectories(report.getParent());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("completedAtUtc", Instant.now().toString());
        document.put("success", failure == null);
        document.put("matrixCasesPassed", results.stream().filter(row -> row.containsKey("mask")).count());
        document.put("copiedFolderDeletionCasesPassed", results.stream().filter(row -> row.containsKey("scenario")).count());
        document.put("seconds", (System.nanoTime() - started) / 1_000_000_000.0);
        var dependencyHashes = new LinkedHashMap<String, String>();
        for (Path jar : jars) dependencyHashes.put(jar.getFileName().toString(), java.util.HexFormat.of().formatHex(digest(jar)));
        document.put("dependencySha256", dependencyHashes);
        document.put("checks", List.of("128 independent Java compilations", "generated service and pane factories",
                "isolated factory classloading", "removed implementations absent", "registration with missing data",
                "commands match retained modules", "GUI-independent services", "actual deletion in copied folders"));
        document.put("limitations", List.of("Native image builds and filesystem/registry qualification are separate tests",
                "The matrix selects Java sources exactly as discovered folders; it does not run Gradle 128 times"));
        document.put("results", results);
        if (failure != null) document.put("error", failure.toString());
        Files.writeString(report, Json.stringify(document), StandardCharsets.UTF_8);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
