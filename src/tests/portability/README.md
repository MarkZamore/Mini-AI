# Приёмка портативной сборки

Эти проверки выполняет разработчик/тестировщик. Трассы и отчёты сохраняются в `src/build/test-results`, не в `docs/db/model`. Приложение запускается обычным пользователем; наблюдатель Process Monitor может иметь отдельные необходимые права.

## Граница проверок

Ноль попыток изменения файлов и реестра со стороны EnterpriseAI, его DLL и контролируемого worker — обязательный критерий. Даже ACCESS DENIED означает дефект. Не путать CreateFile для GENERIC_READ/OPEN_EXISTING с созданием файла. Проверять access/disposition, WriteFile, изменение размеров, rename/delete и записи реестра. Системные действия Windows/AV/драйверов, пользовательский внешний просмотрщик и инструменты испытаний классифицируются отдельно по PID **и стеку**. Не скрывать все события системных DLL автоматически: запись, вызванная собственным кешем приложения, остаётся дефектом.

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

## Process Monitor

1. Не фильтровать только папку приложения: сохранять файловые, реестровые, сетевые и процессные события всего периода.
2. Выделить EnterpriseAI.exe, поставленные DLL и worker внутри Job Object. Отдельно отметить Explorer, Open With, просмотрщик, Windows, защитное ПО и инструменты испытаний.
3. Искать изменения, включая временные SQLite-файлы `-journal`, `-wal`, `-shm`, `sqlite-temp`, извлечение JNI-библиотек, `.swt`, `hsperfdata`, `hs_err`, дампы памяти и пользовательские кеши.
4. Сохранить PML и классификацию в `src/build/test-results` с версией и хешами приложения, Windows и описанием сценария.
5. Сравнить `data-before.json` через `audit_data.py check`. Не запускать `db/validate.py`: он меняет существующие отчёты и каталог.

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

Prepare ProcMon with `procmon_audit.ps1 -Mode Prepare -Profile static
-Executable <frozen EXE> -AdditionalScenariosJson <mock-worker scenarios>`.
Preparation neither elevates nor starts ProcMon. The existing approved
all-events PMC and an ordinary coordinator are still required for Capture.
The static fixtures contain no DLL/CRT copies and use physical index copies.
An optional `-FatalProbeExecutable` must itself pass the static PE gate and
accept `fatal`/`oom` arguments. Keep each raw PML/XML/CSV run and manually review
operating-system stacks, controlled worker process lifetimes, IPC and all
candidate mutations before claiming scoped no-write acceptance.

