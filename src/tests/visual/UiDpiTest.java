package ai.enterprise.tests;

import ai.enterprise.shell.RestyleUiTest;

/** Full-catalog developer DPI fixture. Never changes Windows scaling settings. */
public final class UiDpiTest {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("source-root scale [report-prefix]");
        RestyleUiTest.run(java.nio.file.Path.of(args[0]).toAbsolutePath(), Integer.parseInt(args[1]),
            args.length > 2 ? args[2] : "restyle-dpi", true);
    }
}
