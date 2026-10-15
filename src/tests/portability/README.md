# Приёмка портативной сборки

Эти проверки выполняет разработчик/тестировщик. Трассы и отчёты сохраняются в `src/build/test-results`, не в `docs/db/model`. Приложение запускается обычным пользователем. По решению пользователя Process Monitor исключён из будущего конвейера: UAC, загрузка его драйвера и настройка PMC для сборки, испытаний и публикации не требуются. Отсутствие временных записей и изменений реестра этим конвейером не подтверждается.

## Граница проверок

Требование к приложению сохраняется: оно и контролируемый worker не должны создавать временные файлы, распаковывать библиотеки или записывать настройки в реестр. После исключения Process Monitor будущий конвейер не измеряет все такие операции; отсутствие записей не объявляется подтверждённым. Контрольные суммы доказывают только неизменность проверенных файлов. Windows, защитное ПО, драйверы и внешние просмотрщики находятся за пределами гарантии приложения.

## Матрица

- Чистая Windows 11 x64, без сети, Java, Python, Office и средств разработки; обычная учётная запись.
- Первый и повторный запуск, поиск, граф, отмена, ошибки, закрытие и принудительная авария.
- Перенос папки, ярлык, чужая рабочая папка; кириллица, казахские буквы, пробелы, путь длиннее 260 символов.
- Отсутствие каждой папки данных, повреждение базы или DLL, побочные файлы SQLite, несовместимый manifest модели, нехватка памяти, авария и зависание worker.
- Изменённый оригинал или артефакт, отсутствие файла, выход через относительный путь или junction наружу; проверка SHA-256 перед Open With.
- Все 128 конфигураций функциональных модулей; нативная сборка полного состава, каждого одиночного удаления, отсутствия обеих панелей и минимального ядра.
- Граф: 599 документов, 647 подтверждённых записей связей, 290 ненаправленных пар, 335 изолированных документов; 1579 неподтверждённых ссылок не являются рёбрами.
- История отсутствует после перезапуска; при переполнении бюджета не удаляется молча.
- Встраивание двух независимых экземпляров с раздельными сеансами, повторное использование второго после закрытия первого; отдельная проверка SWT-host.

## Сохранность данных и границы проверки

Сравнить контрольные суммы исходных данных до и после испытаний через `audit_data.py check`. Не запускать `db/validate.py`: он меняет существующие отчёты и каталог. Снимки файлов не обнаруживают кратковременное создание и удаление файлов, отказанные попытки записи или изменения реестра. Исторические трассы и инструменты сохраняются отдельно и не являются этапом будущего конвейера.

Внешний OpenWith не гарантирует переход к странице/ячейке. Выбранная программа может писать кеши и редактировать файл; приложение не выдаёт этот процесс за контролируемый read-only renderer.

Измеренные автоматические проверки и непроведённые испытания должны быть перечислены раздельно. Наличие EXE не является подтверждением полной приёмки.
# Static EXE profile

Run `static-acceptance.ps1 -Executable <frozen candidate EXE> -IncludeGraph` with
PowerShell 7 and the developer observer Python. It exercises physical relocation
without copied DLLs, Unicode/long paths, a real Unicode Windows shortcut, 36
Python-reference search cases, exact fragment/source SHA verification, refusal
after an observer alters only a copied source, and twelve actual standalone
native mock-worker cases (three variants, service OOM, native crash, forced
cancellation). It writes only new `static-*` reports under `src/build` and checks
that the candidate SHA did not change during the suite.

`static_pe.py` checks both ordinary and delay PE imports against Windows inbox
dependencies and observes loaded modules in actual GUI processes. Runtime
snapshots qualify exercised paths; they cannot establish unexecuted dynamic
load behavior, absence of installed software, or absence of transient writes.
`msvcp_win.dll`, `msvcrt.dll` and `ucrtbase.dll` are Windows inbox components;
redistributable `vcruntime140*.dll` and `msvcp140*.dll` remain forbidden.

For module deletion, `../module-removal/static-native-variants.ps1` physically
copies source folders into `src/build`, deletes omitted modules there, performs
clean Gradle/native builds, and tests nine compositions at normal and Unicode
long EXE paths. `-SkipUi` queues GUI tests; later `-SkipBuild` runs the same
already-built variants. The Java matrix accepts a third report-name argument
`static-java-module-removal.json` to retain earlier-profile evidence.

Worker lifecycle fixtures are prepared by `../static-worker/prepare-scenarios.ps1`;
its `worker-scenarios.json` contains all twelve native protocol, OOM, crash and
cancellation cases. Preparation and execution require no Process Monitor,
PMC configuration, driver installation or elevation. Original-data checks and
all other functional, module, import and licence gates remain mandatory.

# Dusty yellow UI / миниИИ observers

Use `static-acceptance.ps1 -Executable <frozen миниИИ.exe> -ReportPrefix <fresh dusty-yellow-run-id> -IncludeGraph` for the new UI. The basename is preserved in every physical relocation/worker fixture. The prefix must be unique for each frozen candidate; existing worker fixture directories are deliberately rejected rather than rewritten. Historical static/D91 reports remain independent.

The native suite includes `native_branding_observer.py` (caption, icon, theme, removed Help chrome), `native_assistant_observer.py` (Enter, Shift+Enter, missing-model error, mock inference citation rejection, and the single Send/Stop control), and the graph observer. Observers dismiss only visible error dialogs belonging to their exact newly launched process; they never target another application's controls. Unexpected asynchronous errors fail the test; dismissing their dialog does not make the application pass.

The graph observer identifies all 64 purple degree shades and their seven documented white-highlight levels; it excludes edges from node components. It checks a single-click full-title footer, selection-ring drag target, pulse pause while dragging, pan/zoom, and double-click source failure only in fixtures without `docs`. After settling it observes at least 30 seconds, requires pixel-exact unchanged geometry/background/strokes with only the documented globally shared 0–6% node/edge color highlight, observes multiple cycles near the 8-second cadence, and records CPU including observer-induced paints. `PrintWindow` itself requests painting, so exact world/frame inactivity and the 5 DIP gap are verified separately by continuous degree-weighted gravity and GraphCanvas/GraphPulse tests. The actual dataset checks cloud occupancy, link lengths and the absence of forced rings; it does not promise a planar graph or zero empty space at every scale. No generic pixel-jitter exemption is used.

The former `static_legal_ui.py` is retained solely for historical builds. The new UI has no Help/licence dialog. Licence and corresponding-source CLI checks remain part of the separate distribution audit.

