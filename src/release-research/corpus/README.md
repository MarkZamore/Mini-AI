# Проверенный перенос корпуса

Эти инструменты готовят и проверяют только `docs/` и `db/`. Они не выбирают
генератор, не публикуют приложение и не изменяют модели.

## Что уже проверено

Подготовленный runtime:

`C:/Users/Oskar/Documents/Mini-AI/src/build/research-20261024/portable-corpus-v2`

Его инвентарь и доказательства:

`C:/Users/Oskar/Documents/Mini-AI/src/build/research-20261024/corpus-portability-v2`

- `corpus-runtime-files.json`: 6476 файлов, 6 560 909 150 байт логического размера;
  относительные пути и SHA-256 каждого файла.
- `assembly.json`: точное происхождение каждого hardlink. Новый payload не
  копировался; исходные файлы остались на месте.
- `acceptance.json`: объединённый результат проверки SHA и реального Java resolver.
- `java-resolver/resolutions.jsonl`: реальные разрешения каждого оригинала и
  каждого пути происхождения; `java-resolver/summary.json`: итог.

SHA-256 инвентаря:

`be47e3cb1b329e801984de2299c3d2e704ff7b6ad7295c2eb09eebebae0737ec`

SHA-256 канонической SQLite до и после проверки:

`58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a`

В составе всех семи модулей фактический `DocumentsService` успешно проверил
599 канонических JSON, 599 оригиналов и 5328 цепочек производных артефактов.
5273 различных файла артефактов обслуживают эти 5328 записей. В 106 139 фрагментах
проверены 179 686 реальных привязок четырёх видов: PDF, OOXML, таблицы и рисунки.
Также разрешён настоящий пример каждого вида привязки.

Среди оригиналов в исследовательском runtime 20 путей длиннее 259 символов;
Java успешно прочитал и проверил их SHA. Обычные Python/Windows вызовы могли
ошибочно сообщать об отсутствии длинных файлов старого clean-install. С
нормализованным абсолютным путём и префиксом `\\?\` все 599 оригиналов старого
clean-install существуют. В текущем корне проекта 19 оригиналов действительно
отсутствовали и после проверки extended paths; корневой `db/artifacts` также
отсутствовал. Подготовленная папка содержит полный проверенный комплект.

**Хеш и допустимая привязка не доказывают качество OCR или смысловую полноту
ответа.** Внешняя программа просмотра в этой проверке не запускалась; фактическое
открытие источника в окончательном интерфейсе проверяется отдельно.

В runtime есть пять `native/` hardlinks только для запуска исследовательского
Java harness. Они отсутствуют в corpus inventory. Поставщик окончательного
приложения должен брать свои библиотеки из утверждённой сборки.

## Планирование и применение дополнений к корню

`deploy.py` привязан к корню этого проекта и указанным SHA. Он сначала проверяет
весь approved inventory, исходники, все существующие целевые файлы и SQLite.
Существующие файлы с совпадающими хешами остаются на месте. Любой отличающийся
файл останавливает перенос до добавления первого файла.

По умолчанию создаётся **только план**. Пример для PowerShell:

```powershell
& C:/Users/Oskar/Documents/Mini-AI/src/build/model-preparation/venv/Scripts/python.exe -X utf8 C:/Users/Oskar/Documents/Mini-AI/src/release-research/corpus/deploy.py `
  --target C:/Users/Oskar/Documents/Mini-AI `
  --source C:/Users/Oskar/Documents/Mini-AI/src/build/research-20261024/portable-corpus-v2 `
  --inventory C:/Users/Oskar/Documents/Mini-AI/src/build/research-20261024/corpus-portability-v2/corpus-runtime-files.json `
  --journal C:/Users/Oskar/Documents/Mini-AI/src/build/research-20261024/corpus-portability-v2/new-deployment-plan.jsonl
```

Для действительного переноса root добавляет `--apply` и выбирает **новый**
файл журнала. Файлы добавляются атомарным `os.link`, без перезаписи. Проверяется
резерв не менее 20 ГиБ, привязка target к workspace, отсутствие reparse points,
SHA каждого добавленного файла и неизменность SQLite. SQLite не открывается
для записи. Ни EXE, ни model, ни файлы за пределами corpus inventory не затрагиваются.

При частичной ошибке добавленные файлы остаются, а журнал сохраняется. Скрипт
не запускает автоматический rollback и не удаляет пользовательские документы.

## Явный rollback только своих дополнений

Сначала получить план по действительному журналу применения:

```powershell
& C:/Users/Oskar/Documents/Mini-AI/src/build/model-preparation/venv/Scripts/python.exe -X utf8 C:/Users/Oskar/Documents/Mini-AI/src/release-research/corpus/deploy.py `
  --target C:/Users/Oskar/Documents/Mini-AI `
  --rollback-journal C:/Users/Oskar/Documents/Mini-AI/src/build/research-20261024/corpus-portability-v2/actual-deployment-apply.jsonl `
  --journal C:/Users/Oskar/Documents/Mini-AI/src/build/research-20261024/corpus-portability-v2/new-rollback-plan.jsonl
```

Только дополнительный `--apply` удалит перечисленные дополнения. Для каждого
требуются запись `added`, прежние file ID/device/размер/mtime и тот же SHA.
Заменённые или изменённые файлы не удаляются. SQLite никогда не удаляется;
папки также не удаляются. Если процесс оборвался между hardlink и записью
`added`, pending path остаётся для отдельного аудита: rollback не угадывает
владельца такого файла.

## Воспроизведение проверки

- `../corpus_portable.py` создаёт новую отдельную папку. Точные исходники
  берутся из canonical root и старого clean-install; при отсутствии локального
  файла доступен pinned `EnterpriseAI-data.zip`. Проверяются SHA ZIP и его
  инвентаря, затем SHA всех файлов и канонические привязки.
- `CorpusResolver.java` компилируется отдельно, не заменяя основные classfiles,
  и запускается с текущим полным `GeneratedCatalog` и настоящим `WindowsPlatform`.
  Вызов: `ai.enterprise.documents.CorpusResolver runtime-root new-results-folder`.
- Инструменты не читают sealed-final, не используют GPU и не меняют настройки
  Windows. Для длинных путей Python применяет `\\?\` на уровне конкретного вызова.

Hardlinks разделяют содержимое с проверенными исходниками. Не изменять файлы
в исследовательском runtime: редактирование затронет и исходную ссылку.
