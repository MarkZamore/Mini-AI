EnterpriseAI — provenance and redistribution inventory

The application source is supplied in src. No licence for the owner's entire
application is inferred from the licences of its dependencies. Dependency
notices and their source/licence obligations remain applicable.

Pinned initial components:
- GraalVM Community OpenJDK 25.0.2+10.1 / nativeimage SDK 25.0.2:
  GPLv2 with Classpath Exception and component-specific notices. The build
  copies the distribution's LICENSE, third-party notices and legal directory.
  Upstream: https://github.com/graalvm/graalvm-ce-builds/releases
  Sources: https://github.com/oracle/graal and https://github.com/openjdk/jdk
- SWT Win32 x64 3.135.0: Eclipse Public License 2.0. The distribution contains
  upstream source JAR/about notices plus the modified strict Library.java.
  This patch removes extraction and fallback loading. Upstream:
  https://github.com/eclipse-platform/eclipse.platform.swt
- SQLite 3.53.1 amalgamation: public domain. The optional database module
  carries its notice and narrow JNI/VFS source. No SQLite conversion tools
  are included in the runtime.
- Optional search resources: Unicode 15.0 data and Russian Snowball 3.1.1
  algorithm. Their notices/provenance are supplied by the search module.

The build inspects Windows DLL imports. App-local MSVC redistributables are
needed only if imported and must follow Microsoft's redistribution terms.
Current own DLLs use /MT; do not infer requirements from the build computer.

No model, model engine, Office renderer, Python, OCR or LibreOffice is included
in this initial application distribution. Independently prepared model files
must have their own licence/notice/source provenance and SHA-256 inventory.
Qwen3.8-27B is the provisional model candidate; its actual prepared revision
and derivative licence conditions are recorded during separate preparation.

All developer tool caches/downloads are under src/build and excluded from the
portable runtime. Existing db/_runtime remains unchanged and is not executed.
