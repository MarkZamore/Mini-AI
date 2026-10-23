# Полная portable-поставка Mini-AI

Этот pipeline не изменяет исходный runtime, не публикует Git и не загружает
GitHub assets автоматически. Применять к зафиксированному runtime с явным
решением о поставке.
Старые src/distribution/package_laptop.py и Setup.ps1 не изменены.

Текущее решение поставки — **operational fallback** к ранее зафиксированному
контролю с исходной E5 (`final-control-untrained-v1`), а не принятие дообученной
E5 как победителя. Улучшение поиска после FT не обеспечило подтверждённый
выигрыш ответов и не прошло ограничение критических ошибок. Надёжность
нормативных ответов не подтверждена и для исходного контроля. Исходный freeze
оценки сохранён; отдельный `deployment-decision-v2.json` фиксирует это решение.

## Подготовка runtime для окончательной проверки

Запускать из корня проекта. Команды используют имеющийся Python разработчика.
Конечному пользователю он не нужен.
Текущий `final-control-untrained-v1` уже создан и проверен; не создавать его
повторно. Ниже пример первичной подготовки нового снимка с новым именем.

    $py = 'src/build/model-preparation/venv/Scripts/python.exe'
    $research = 'src/build/research-20261024'
    $model = "$research/final-control-untrained-v1/model" # ранее оценённый foundation control
    $corpus = "$research/portable-corpus-v2"
    $inventory = "$research/corpus-portability-v2/corpus-runtime-files.json"
    & $py -X utf8 src/release-research/portable.py stage --runtime-root src/build/staging/EnterpriseAI --model-root $model --corpus-root $corpus --corpus-inventory $inventory --output "$research/prepared-runtime-NEW-v1"

Выход должен быть новым. Берутся EXE, присутствующие root CRT DLL,
application native/licenses, ровно worker.json+worker.files, ровно corpus files.
Все входные SHA проверяются. Используются только NTFS hardlinks; при невозможности
hardlink операция прекращается, скрытого копирования гигабайтов нет.
Манифест stage находится снаружи runtime: `<runtime>.stage.json`.
Не менять hardlinked payload на месте: для изменяемого JSON применять замену
файла новой копией, как это делает поставляемый переключатель профиля.
В runtime проверять только полный состав из семи модулей.

## План поставки и фиксация

После окончательных проверок EXE и выбранных весов:

    $tag = 'v2026.10.24-local-ai' # root выбирает новый свободный immutable tag
    $configuration = 'mini-ai-20261024-4b-base-e5-foundation-fallback-v1'
    $runtime = "$research/final-control-untrained-v1"
    & $py -X utf8 src/release-research/portable.py plan --runtime-root $runtime --model-root "$runtime/model" --corpus-root $runtime --corpus-inventory $inventory --output "$research/release-preparation/portable-plan-foundation-v1.json" --tag $tag --configuration-id $configuration

Plan содержит все абсолютные источники, SHA, размеры и параметры бюджета.
До окончательных gates не создавать фиктивный freeze. Существующий исходный
freeze оцениваемых конфигураций не переписывается. Старый режим build без
`--decision` по-прежнему проверяет точное совпадение с его final-конфигурацией:

    {
      "state": "final-frozen",
      "configuration_id": "<точное значение plan>",
      "app_sha256": "<точное значение plan>",
      "model_manifest_sha256": "<точное значение plan>",
      "corpus_sha256": "<точное значение plan>"
    }

Дополнительные evidence поля допустимы. Этот JSON подтверждает решение root,
а не сам по себе доказывает качество: реальные тесты/скриншоты/оценка нужны отдельно.

Режим `--decision` отдельно допускает только явный `operational-fallback` к
`configs.untrained`: неизменённый runtime/backend/модель, flags изменений false,
ссылка bytes/SHA на настоящий исходный freeze. Plan должен точно совпасть со
всеми 6811 rows pinned stage. Все 6789 исходных frozen rows неизменны; остальные
22 длинных пути разрешены только для original docs и подтверждаются coverage
companion, SHA уже frozen document JSON, source path/size/SHA внутри этого JSON.
Произвольные дополнительные файлы так разрешить нельзя. Release metadata
сохраняет исходный freeze SHA и отдельно decision SHA/статус/ограничение качества.

## Архивирование после freeze

    & $py -X utf8 src/release-research/portable.py build --plan "$research/release-preparation/portable-plan-foundation-v1.json" --freeze "$research/final-evaluation-preparation/FREEZE-v1.json" --decision "$research/deployment-decision-v2.json" --output D:/Mini-AI-release-20261024-foundation-v1
    & $py -X utf8 src/release-research/portable.py verify --deep --output D:/Mini-AI-release-20261024-foundation-v1

Каталог должен быть новым на D:. На C: требуется свободно минимум 20 ГиБ.
Payload ZIP64 пишется без промежуточной копии в части по 1792 МиБ; каждая
строго меньше 2 ГиБ. Файлы сохраняются без ZIP compression для предсказуемого
дискового бюджета и быстрого чтения. Дополнительно нужны примерно 1 ГиБ
резерва на D:. Неполный выход сохраняется и не объявляется готовым.
Сборка архива не возобновляется внутри старой директории: применять новый
версионированный output. Не удалять старые результаты автоматически.

Публиковать только файлы, перечисленные в publication-assets.json:

- Mini-AI-setup.zip;
- все Mini-AI.payload.partNNN;
- SHA256SUMS.txt.

Не публиковать bootstrap как отдельный дублирующий архив, build-state,
source paths, research adapters/checkpoints, toolchains или внутренние логи.
Отчёт может быть отдельным опубликованным Markdown artifact по решению root.
Официальное ограничение GitHub: [каждый asset меньше 2 ГиБ](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).

## Публикация root

Проверить тот же origin MarkZamore/Mini-AI, уже имеющуюся gh auth, актуальный
commit, отсутствие существующего tag и source consistency. Сначала commit/push
исходников без гигантских blobs, затем tag и draft release. Например:

    gh release create $tag --repo MarkZamore/Mini-AI --verify-tag --draft --title 'Mini-AI: локальный помощник' --notes-file <готовый-release-notes.md>
    $assets = Get-Content D:/Mini-AI-release-20261024-foundation-v1/publication-assets.json -Raw | ConvertFrom-Json
    foreach ($asset in $assets.assets) {
      gh release upload $tag (Join-Path D:/Mini-AI-release-20261024-foundation-v1 $asset.name) --repo MarkZamore/Mini-AI
      if ($LASTEXITCODE -ne 0) { throw 'Upload failed; leave the release as draft.' }
    }

Не использовать --clobber. При повторе сначала прочесть список уже загруженных
assets; совпадение имени/размера недостаточно, проверить GitHub SHA-256 digest,
либо потоковый SHA скачивания. Загрузить только отсутствующие. До финального
publish проверить все имена/размеры/SHA против publication-assets.json.
Затем root публикует draft и проверяет публичные pinned URL из portable-release.json.
Никакого запроса новой авторизации, UAC или облачного обучения не включать.

## Установка пользователя

Пользователь скачивает только setup ZIP, распаковывает его и открывает
Установить-и-запустить.cmd. Inbox Windows PowerShell 5.1 скачивает части
диапазонами 16 МиБ с ограниченными повторами и продолжением после обрыва.
Проверяются SHA pinned metadata, частей, inventory и каждого runtime файла.
ZIP читается непосредственно через seekable multipart stream без склейки
ещё одного полного архива. Прерванное извлечение продолжается через только
принадлежащие этой inventory .partial; чужие partial/несовпадающие final сохранены.
Поддержаны extended Windows paths, Unicode, запрет traversal/reparse paths,
пересекающихся операций, посторонних ZIP entries.

По умолчанию выбирается CPU. Запустить-на-NVIDIA.cmd выбирает CUDA.
Профили лежат отдельно в profiles, их SHA pinned в release metadata.
Все weights/files/evidence и состав приложения одинаковы. Меняется только backend
через атомарный File.Replace после проверки closed app/worker и known manifest.
Предыдущий manifest сохраняется как backup; неизвестный worker.json не меняется.
Повторная проверка установщика принимает только два поставленных профиля.
CPU throughput на другом ноутбуке не обещан; full-RAG CPU проверка root отдельная.
Нужно не менее 9 ГиБ свободной RAM; поставленный CUDA-профиль требует совместимого
драйвера и не менее 11 ГиБ свободной VRAM перед загрузкой (бюджет 8 ГиБ плюс
резерв 3 ГиБ). Карты с 6–8 ГБ памяти этот профиль не поддерживают, для них есть
CPU. Ни одна системная настройка не изменяется.

## Проверенные границы

portable_probe.py проверяет малые транспортные byte fixtures настоящим inbox
PowerShell. Это не запуск Mini-AI, не оценка модели и не замена финальной
проверки GUI/цитат/отмены/Teams. В sealed-final pipeline не обращается.
Полную дополнительную распакованную копию многогигабайтного runtime создавать
не обязательно: root может сверить payload stream/SHA с уже проверенным stage,
не нарушая C:>=20 ГиБ. Проверку сетевых публичных URL выполнить после публикации.
