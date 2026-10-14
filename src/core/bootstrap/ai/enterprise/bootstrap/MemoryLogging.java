package ai.enterprise.bootstrap;

import java.util.logging.*;

/** Suppresses inherited JUL handlers; the component stores its own diagnostics in RAM. */
public final class MemoryLogging {
    public MemoryLogging() { var manager=LogManager.getLogManager(); manager.reset(); Logger.getLogger("").setLevel(Level.OFF); }
}
