# Статическая среда сборки

Получателю нужны только основной EXE и папки данных. Приведённые ниже инструменты
используются разработчиком; их файлы и промежуточные результаты находятся в `src/build`.

Версии: GraalVM CE 25.0.2+10.1, MSVC 14.51.36231, Windows SDK 10.0.26100.0.
Ревизии и SHA-256 upstream-архивов закреплены в `static-sources.json`.
Исходная GraalVM находится в `build/tools`; она не изменяется.

Для сборки OpenJDK используется Cygwin с bash, autoconf, make, zip и unzip в
`build/static-toolchain/cygwin`. При подготовке Cygwin используются официальная
утилита, `--no-admin --no-shortcuts --no-write-registry` и корень внутри `src/build`.
Это инструменты разработки, а не компоненты приложения.

Из папки `src` выполнить последовательно:

```powershell
python -B toolchain/prepare_static_sources.py
python -B toolchain/prepare_jdk_static.py
& build/static-toolchain/cygwin/bin/bash.exe --noprofile --norc toolchain/configure_jdk_static.sh
& build/static-toolchain/cygwin/bin/bash.exe --noprofile --norc toolchain/build_jdk_static.sh
& toolchain/build_static_toolchain.ps1 -Action stage
python -B toolchain/rebuild_svm_jvm.py
& toolchain/static_runtime_gate.ps1 -Action build
python -B toolchain/runtime_probe_acceptance.py
python -B toolchain/build_static_libraries.py
& build.ps1 -Action build -Profile static
```

`stage` заменяет изолированную копию toolchain и возвращает исходные JDK-архивы;
после него обязательны пересборка shim и runtime gate. Не выполнять `stage`,
пока другая сборка использует эту копию. Runtime gate копирует проверенные `/MT`
архивы Java base, jdk.net и jdk.crypto.mscapi. Основная сборка повторно проверяет
все выбранные JDK/SVM/JNI-архивы и запрещает конфликты CRT.

Новая сборка находится в `build/static-image`. Её успешная компиляция не является
приёмкой: требуются перенос, поиск, интерфейс, сменные worker,
лицензии и сохранность данных. Process Monitor исключён из обязательного конвейера
по решению пользователя; публикация не подтверждает отсутствие кратковременных
файловых и реестровых записей. Публикация проверяет
`build/test-results/static-release-acceptance.json`, SHA EXE и хеши свидетельств.
Старые принадлежащие приложению файлы предварительно сохраняются в `src/build/publication`.
`docs`, `db` и `model` публикация не копирует, не удаляет и не перезаписывает.

Проверяется только текущая версия в текущем составе. Проверки сочетаний модулей,
удаления папок модулей и альтернативных версий исключены из обязательного конвейера
по указанию пользователя. Автоматическое обнаружение модулей при сборке сохраняется.
