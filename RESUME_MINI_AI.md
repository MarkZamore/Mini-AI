# Контрольная точка Mini-AI перед перезапуском Codex

## Возобновление после перезапуска — актуальный статус

### ЗАВЕРШЕНИЕ: комплект и отчёт опубликованы, публичная доставка проверена

- Публичный экспериментальный prerelease:
  https://github.com/MarkZamore/Mini-AI/releases/tag/v2026.10.24-local-ai .
  Release ID422387026, draft=false, prerelease=true. Source/tag commit
  `cbd15cd875bfe3444cb2522df8af89cc19e4105b`; последующие изменения только MD.
- Все 11 assets (9 приложения + ZIP отчёта и его sidecar) по API совпали
  по имени, размеру и SHA. `release-preparation/github-published-foundation.json`.
  Независимые public checks: все 16 HTTP Range, точные pinned URL и полный SHA
  setup/SHA256SUMS PASS. Receipt `github-public-check-v1/verification.json`,
  SHA `b7669013f0652181dc4b1413eeef69740855d76f4a0cdc85ffec84dfca812a6c`.
  Отчёт ZIP и его sidecar полностью скачаны без авторизации и SHA-проверены:
  `github-public-report-check-v1.json`. Большие части повторно целиком не скачивались.
- Markdown-отчёт на Desktop завершён: 2321 слово, 256 относительных ссылок PASS,
  пять baseline и пять actual foundation GUI ответов дословно, 50 карточек,
  18 финальных raw +18 точных crops. ZIP 29160553 B, 670 entries CRC+SHA PASS;
  SHA `4a7f3c97353d632f964a45b21374754a7d6e8c04ac9dceb53cd1064e423a5369`.
  Локальный ZIP `report-release-v1/Mini-AI-research.zip`, опубликован тем же файлом.
  Веса и канонический корпус в отчёте не дублировались. Замороженные Desktop/ZIP
  не менять; поздние квитанции публикации сохраняются отдельно.
- ROOT EXE160219…, manifest91eef…, SQLite58d6…, userinstructions958d… после GUI
  и CPU совпадают с утверждёнными SHA. Все Mini-AI/workers завершены, lease снят.
  Старые модели/checkpoints, исходные документы и обе резервные конфигурации целы.
- Исследовательский план завершён с отрицательным результатом приёмки качества:
  FT поиска улучшил retrieval, но не доказал прирост качества полных ответов.
  Поставлен frozen foundation operational fallback. Нормативная надёжность
  НЕ доказана. Все пять GUI имеют warning неполноты; название организации
  потеряно в первом, в пятом оборван пункт. Не исправлялись по открытому final.
  Нет проверки человеком, настоящего звонка Teams и запуска на целевом ноутбуке.
  Это ограничения результата, а не выполненные проверки.

Ниже — сохранённая история. При противоречии действует этот верхний блок.

### Актуально: foundation уже в корне, архив проверен, завершается демонстрация и публикация

- ROOT успешно переведён на `mini-ai-20261024-4b-base-e5-foundation-fallback-v1`.
  `root-promotion-foundation-v1/acceptance.json`: 335 файлов проверены, прежние
  шесть единиц сохранены в backup; модельный manifest `91eef09c28743ebae6a8ab251e841cc0f8b8446681e3831de77ed879457fe82a`.
  Это operational fallback, нормативная надёжность не доказана. Production после
  original FREEZE не изменялся и по ошибкам финального теста не настраивался.
- Новый комплект `D:/Mini-AI-release-20261024-foundation-v1` готов. Deep verify:
  все 6811 файлов и девять publication assets PASS. Публиковать ТОЛЬКО его
  `publication-assets.json`, SHA `549511b24c076eae23289dc373e820e86dc907d32121a10bfd614e305c8636b0`.
  Setup SHA `ba2c95809c0ffe5a73de1985ec6e32252023a5dbc126d4938dcb7b954d0ea2f8`.
- generator_rag завершил все пять настоящих final foundation GUI-диалогов,
  50 карточек, 18 raw и 18 точных crops. Desktop final/demo-01..05.json готовы;
  все пять показывают предупреждение неполноты. Ошибки не скрывать. Все приложения
  закрыты; guardian `final-gui-foundation-session-v1` session80714 exit0,
  `complete.json` подтверждает lease_released, gpu.lock отсутствует.
- source_portability завершил ровно один точный foundation CPU full-seven запрос,
  exit0. First nonempty 174.8650608 s, full 203.5852019 s, 2199 chars/10 sources,
  качество unknown и warning неполноты. Peak tree RSS 5121536000 B, минимум
  свободной RAM 16913657856 B. Live ONNX+ggml-cpu, без CUDA. Teams idle, звонка нет.
  Worker profile SHA3217e5… и frozen control manifest91eef… unchanged, workers=[],
  lease отсутствует. Артефакты `cpu-foundation-probe-v1/run`. Не повторять.
  Старые 174/224 секунды относятся к отклонённому trained-кандидату.
- Markdown-отчёт на настоящем Desktop: 2284 слова, девять приложений, 183 ссылки
  проверены. report_finish добавит пять GUI, точный CPU и публикацию, затем ZIP.
- Исходники уже опубликованы: commit `cbd15cd875bfe3444cb2522df8af89cc19e4105b`,
  main и codex branch, annotated tag `v2026.10.24-local-ai`. Draft prerelease
  ID `422387026` (пока не public), setup уже uploaded/digest PASS. Root uploader
  exec session `39944`, журнал `release-preparation/github-upload-foundation.jsonl`;
  два параллельных streaming uploads, без clobber. Дождаться всех девяти assets,
  сверить API SHA/size, затем report ZIP и только после этого publish.
  API lookup draft через releases/422387026, не releases/tags (возвращает 404).
  19 устаревших runtime файлов model убраны только из Git index (git rm --cached);
  реальные файлы и резервные копии сохранены. Проверка source commit/tree,
  frozen source/JAR, секретов и quality claims: source-publication-audit-v1.json PASS.
- C свободно около 23.6 GB, D около 1.57 GB. Никаких новых больших копий.
  Четыре части старого trained-архива сохранены на C, остальные на D согласно
  `retained-trained-archive-v1/retained-layout.json`; ни один байт не потерян.

Ниже сохранена история. При противоречии действует этот самый верхний блок.

### САМАЯ НОВАЯ ТОЧКА: кандидат отклонён, поставка возвращается к frozen foundation control

- Все12/12 финальных jobs и combined scoring завершены, runtimeerrors0.
  `final-evaluation-run-v1/challenge-reviewed-all-v1/metrics.json` SHA
  `08a1dda1d0dc38ea5b320efb48628f96add0b4cd79f9086dfe07ef013544273b`.
  TrainedE5 улучшила actual-context Recall56→95%, но полные ответы6/28=6/28,
  парный95%CI[-20;+25]п.п.; critical4(trained) против3(foundation), unknown19/30
  у обоих. Уlexical0/28,critical10; boundedу всех3/4correct+1unknown.
- По ПРЕДЗАДАННОМУ правилу accept/reject root отклонил trainedE5 для поставки
  и вернулся к ЗАРАНЕЕ FROZEN role`untrained`, без нового тюнинга. Это operational
  fallback, НЕ доказанно надёжный нормативный помощник. final_evaluation независимо
  подтвердил методологическую допустимость и точные metadata. Все исходные scores,
  веса, индекс, FREEZE1fb75… сохранены, не переписывать историю.
- Актуальное решение `deployment-decision-v2.json`, SHA
  `7d2a6e2a7f8cf88c0fbbda551199cad750a23a87bba58ba6b64280fc225965dc`.
  V1b26b… сохранён. Поставляемый ID
  `mini-ai-20261024-4b-base-e5-foundation-fallback-v1`, runtime
  `final-control-untrained-v1`, manifest
  `91eef09c28743ebae6a8ab251e841cc0f8b8446681e3831de77ed879457fe82a`.
  EXE160219… и corpus58d6… те же. V2 связывает originalFREEZE, полныйstage6811
  и postruncoveragecompanion. Frozeninventory6789+22longdocs; все22actualSHA
  независимо совпали с originalsourceSHA из frozen document JSON.
- ROOT ПОКА ЕЩЁ TRAINED candidate после `root-promotion-final-v1` (335files/6units),
  manifest02538…; oldoriginal EXE/model/native/licenses/CRT целиком сохранены
  в backup, SQLite и instructions неизменны. Foundationpromotion ещё НЕ выполнен.
  `root-promotion-final-v1/acceptance.json` подтверждает первое promotion,
  но НЕ качество. Следующий output `root-promotion-foundation-v1`.
- Trained native GUI: первый вопрос дал неполный ответ без названия организации.
  Его2rawJPEG+JSON перенесены из Desktop в `rejected-trained-gui-v1` с manifest,
  НЕ выдавать их за shipped final и не включать промежуточные кадры в отчёт.
  Тот app/workers закрыты; guardian session29689 завершён exit0, lease снят.
- Source_portability владеет расширением `promote_root.py --decision` (старый
  freeze-режим сохраняется) и rootREADME. Final_evaluation уже независимо прочёл
  новый helper и decisionv2: PASS; audit `release-guard-audit/decision-v2-audit.json`.
  Ждать helper-owner ready/dry-run и применить root после проверки отсутствияPID/lease.
- Portable_release_finish готовит `portable.py build --decision` с настоящим
  linkedFREEZE/keyuntrained/fullstage gate, без fakefreeze; installertransport
  не меняется. Новый output `D:/Mini-AI-release-20261024-foundation-v1`.
  Предыдущий trained archive12.205GB уже build/deepverifyPASS на Dfinal-v1, но НЕ
  опубликован. Для места owner сохраняет4точныхparts001..004 (7.516GB) на Cresearch
  `retained-trained-archive-v1` черезcopy→SHAverify→unlinkТОЛЬКОсвоих4archivefiles,
  с layout/journal. Остальныеparts/metadata остаютсяD. Все weights/trainingresults
  сохраняются. ПрогнозC23.65GB>20GiB/D13.77GB>newpayload+1GiB, guards обязательны.
- **Единственный Windows UI owner теперь generator_rag**, root Sky не использует.
  Он читает `HANDOFF-final-gui-foundation.md` и ЖДЁТ отдельного сигнала root:
  сначала foundationpromotion, затем новый guardian `final-gui-foundation-session-v1`,
  после этого пять новых настоящих ROOT GUI диалогов/10sourcecards/raw/crops.
  Сейчас пять foundationfinal ещё НЕ сделаны. Baseline7raw+7cropped сохранены.
- Report_finish обновляет MD под foundation fallback; основное содержание около
 2254слов, mainsemanticunknown, 6/28autoнеaccuracy, критические ошибки явно описаны.
  Дляпоставки latency=untrained TTFTmedian/p95 4.183/8.260s, full6.009/10.965s.
  Это frozenfull7JavaRAG, не nativeGUIlatency. Teams signed-in idle, звонка нет.
- GitHub/commit/tag/push ЕЩЁ НЕ сделаны. Originmain c92dbff; branch
  codex/local-ai-research-20261024, gh authMarkZamore уже имеется. Tag
  v2026.10.24-local-ai свободен. Root должен закончить fiveGUI/reportMD+bundle,
  sourcecommit/push и новыйfoundationrelease/digests/publicURLs; не публиковать
  старыеcandidateassets по ошибке. Release notes ещё требуют foundation wording.

Ниже — более ранние сохранённые стадии; при противоречии действует блок выше.

### Позднейшая контрольная точка: финальный отложенный тест выполняется

- Конфигурация `mini-ai-20261024-4b-base-e5-ordinary-final-v1` зафиксирована:
  `final-evaluation-preparation/FREEZE-v1.json`, SHA
  `1fb75aab2a04e42d655ee272d0a29e3d380ec7e422400d0d5fda5ae275e619d9`.
  Spec SHA `ee936043128d50ebfd0fa017e7e0f4b287fff5a5248a5cb676d3a9785cecf0fb`.
- Sealed100 ОТКРЫТ после фиксации, 2026-10-23T22:01:22Z; SHA совпал с
  публичным манифестом. Процессом владеет агент `final_evaluation`, exec session
  29724, lease `frozen-final-evaluation`. Последний сигнал: final/answer100
  завершён100/100 exit0; final/challenge-full-rag завершён30/30 exit0,
  bounded4 выполняется. Ошибок и перезапусков нет. Не запускать дубликат.
- Три полных конфигурации final/untrained/lexical проходят answer100, search100,
  challenge30 full-RAG и challenge4 bounded. Выход `final-evaluation-run-v1`.
  После открытия теста не менять production-код, веса, индекс, prompt и пороги.
- Teams авторизован и работает idle, реального звонка нет. Последние samples:
  VRAM used4636MiB/free11413MiB, availableRAM16.6GB; два worker-процесса —
  generationCUDA и evidenceCPU, а не два GPU-генератора.
- Публикация/продвижение root EXE/model, финальные пять настоящих GUI-примеров
  и окончательный Markdown пока впереди. source_portability обновляет README,
  report_finish — Desktop отчёт/reproduce, portable_release_finish готовит план
  архива, но ждёт разрешения начать большой IO после всех оценочных прогонов.
- Уточнение: большой IO отложен до конца ВСЕХ12jobs, чтобы не искажать парные
  показатели задержки. Portable план/hash/archive всё ещё не запускались.
  `src/release-research/promote_root.py` готов и проверен dryrun335files/6units,
  но rootpromotion НЕ выполнен. Старые root EXE/model всё ещё сохранены на месте.
- Source-based automatic review finalchallenge: generator_rag первые17cases
  исходного порядкаrevision2, source_portability последние17. Выходы ожидаются
  `final-challenge-reviews/final-part1.jsonl` и `final-part2.jsonl`. Не считать
  самостоятельной человеческой проверкой; scorer/revieworigin фиксированы.
- Root создал7croppedPNG только из ранее сохранённых baselineJPEG, без новых
  кадров/resize/маскировки. `screenshots/baseline-crop-manifest.json` в Desktop
  фиксирует SHA и rectangle(10,30,750,970), raw сохранены.

- Final native EXE SHA160219d9351faad98b57c2c26394f13a4ea3e79af9172d794b1502e390a0dccd,
  314363904bytes;226+policy/streamPASS. Полный runtime `final-runtime-v1`:6811files,
  12,202,736,500logicalbytes, hardlinks. Native CLI status/DB/model, реальный
  E5search и CUDAgeneration,8fragment/resolvecalls4locator kinds PASS.
- Реальный SWT/native GUI отменён при уже видимом generated answer; следующий
  запрос в ТОМ ЖЕ окне завершён, карточка цитаты открыта. Артефакт
  `final-native-smoke/gui-lifecycle.json`; intermediate PNG не сохранялись.
  GUI appPID19384 завершён, его GPU lease освобождён.
- Teams запущен и уже авторизован; idle, **звонка нет**. Оставлен работать.
- Реальный context overflow→next10PASS. CPUfullRAG1 с Teams: first174.326s,
  full224.006s, ответ помечен неполным. Функциональный fallback, **не проходит
  latency15/60**, не semanticPASS. PeakRSS4.77GiB, minfreeRAM15.04GiB;
  фактические CPUworker modules безCUDA проверены.
- ВROOTуже применён корпус:5292missing hardlinks добавлены,1184existingmatching
  сохранены, SQLiteSHA прежний. Журнал `corpus-portability-v2/root-deployment-apply.jsonl`.
  ROOT EXE/model всё ещё старые; их promotion/rollback впереди.
- Дополнительный challenge утверждён ДОответов: `challenge-v1/revision-2`,
  manifestSHA98b2b251fa1fe2f5641914a6c937ec60c1bf8c899ed4f2bc5e8b88b306bac1e3;
  casesSHA0ef1162e28b8191b8fa74ace249bafe57087440c8f2b7b2f499501cb8bfdd6fc.
  34cases=28fullRAGanswer+2clarify+4boundedinsufficient;24RU/10KZ,
  11DOCX/9families внеtrain. Independent automatic pre-answer audit PASS,
  `source-audit/delta-review.json` SHAa2ccac0d3accb04a9ddc14a5e56749017fe77b33a12ec5243de177a6033eae8d.
- Полные control runtimes готовы: `final-control-untrained-v1` manifest91eef09c…
  и `final-control-lexical-v1` manifest0ef05475…, все6811assets/7modules.
  Neighborcontext dev20:7expanded changed; mandatorycoverage20/20 у обоих,
  gain0, это только retrieval/evidencecontrol, не answerquality.
- Root ЯВНО разрешил final_evaluation агенту зафиксировать spec/FREEZE и затем
  открытьsealed100. Фактические freeze и запуск подтверждены выше. Проверять
  живой агент/новые файлы перед любым своим run; не дублировать evaluation.

Работа возобновлена 24 октября 2026; доступны шесть субагентов (включая дочерних).
Старый текст ниже описывает сохранённую паузу и не является текущим статусом.

- Полный Java состав пересобран. Paired RAG20 `generator/dev-rag20/paired-v1`
  и `paired-v2` завершены: одинаковые упорядоченные источники 20/20, все семь
  модулей, неизвестных ссылок и runtime ошибок нет. V2 literal base6/20, A10/20,
  CI прироста включает0; A зацикливается на нерелевантной фразе. Выбран base4B;
  E5 ordinary seed1 + RRF остаётся поиском. Это не доказательство широкой
  семантической приёмки; реальные ошибки сроков/адресатов сохранены в review.
- Исправлены SOURCE и регрессии: punctuation/paragraph loss после цитат,
  явные вопросы «в документе…» теперь нормативные; при найденных источниках,
  но отсутствии цитированного ответа не сообщается, будто источников нет.
  Последняя правка включается в финальную native сборку агентом full_build.
- Корпус `portable-corpus-v2`: 6476 files, SHA inventory
  be47e3cb1b329e801984de2299c3d2e704ff7b6ad7295c2eb09eebebae0737ec.
  Реальный DocumentsService/full7 проверил 599 originals,599 JSON,5328 chains,
  179686 source_refs. Root19missing originals и artifacts ещё НЕ развёрнуты.
  `src/release-research/corpus/deploy.py` dry-run:1184 existing matching,
  5292 missing; journalled hardlink-only apply пока не запускался.
- `src/release-research/portable.py` stage/plan/build/verify --deep,
  standalone PS5.1 installer + CPU/CUDA profiles готовы; транспортные small
  fixture проверки PASS. Больших архивов/upload ещё нет; default install CPU.
- `src/research/final_evaluation.py` готов, sealed-final всё ещё НЕ открыт.
  Дополнительный разнообразный challenge готовится report_finish и детьми
  до freeze; его использовать только для оценки после freeze, не для подбора.
- Desktop Markdown draft2057 слов +6 приложений, все57 ссылок существуют.
  Final/Teams/UI/публикация ещё pending. Native app/root model ещё прежние.

При следующем продолжении сначала проверить live agents/их процессы и новые
handoff/result files: активный build или RAG нельзя дублировать по старому тексту.

Сохранено 24 октября 2026 года по прямому запросу пользователя о паузе.
Все три субагента остановлены после записи handoff. Обучение завершено,
активных исследовательских Python/Java/worker-процессов и GPU lease нет.
Проект НЕ объявлен завершённым. Корневое приложение и рабочая папка model
ещё исходные: новые кандидаты пока находятся в исследовательской папке.

## Где продолжать

- Проект: `C:/Users/Oskar/Documents/Mini-AI`.
- Ветка: `codex/local-ai-research-20261024`; исходный HEAD `c92dbff84a5ff850c9e324b2228fcec00b6beea2`.
- Исследование: `src/build/research-20261024` (игнорируется Git).
- Отчёт и иллюстрации: `C:/Users/Oskar/Desktop/Исследование_локального_ИИ_2026-10-24`.
- Черновой `Отчёт.md`: около 1950 слов, ещё НЕ итоговый.
- Подробные handoff: `HANDOFF-audit_research.md`, `HANDOFF-model_options.md`,
  `HANDOFF-audit_architecture.md` в папке исследования.
- Снимок изменённых/новых исходников, Git diff и manifest состояния:
  `src/build/research-20261024/restart-checkpoint-20261024/`.

## Неизменяемые требования

Продолжить пользовательский план автономного улучшения поиска, локального
обучения и проверяемых ответов. Сохранять все существенные условия, сроки,
исключения, адресатов и действия. Сокращать служебный шум, не содержание.
Соблюдать AGENTS.md: только полный состав из семи модулей; никаких
module-removal, матриц состава, отдельных graph tests и Process Monitor.
Не удалять документы пользователя, прежние модели, checkpoints и результаты.
Не проходить авторизацию, не менять настройки безопасности, не требовать UAC.

Скриншоты нужны ТОЛЬКО исходного и окончательного приложения; промежуточные
эксперименты сохранять текстом/метриками. В отчёте сравнить время ответа
до/после; разделять startup, настоящий первый текст, полный ответ и длину.
Не указывать длительность проекта или обучения. Итоговый отчёт только Markdown,
1800–2400 слов основного текста плюс приложения, рассказ и FAQ.

Независимого человека-проверяющего нет. Автоматическая проверка, существующая
цитата и буквальное совпадение не доказывают смысловую полноту нормативного
ответа. Не объявлять неизвестную оценку успешной.

## Что уже сделано

1. Сохранены исходный EXE, manifest, патч и пользовательские исходные правки.
   Создана рабочая ветка; исходные docs/db не изменялись.
2. Получены пять НАСТОЯЩИХ baseline ответов в интерфейсе, JSON и raw-снимки:
   Desktop `baseline/demo-01.json` … `demo-05.json`, `screenshots/raw/before-*`.
   Это демонстрация, не слепой тест и не чистый замер задержки (были фоновые работы).
3. Teams завершён на время обучения. После обучения ещё не запускался для
   окончательной демонстрации. Настоящий звонок не проверен.
4. Реализованы EvidenceModelServiceV1, единый native worker с CUDA generation
   и CPU ONNX embeddings/reranking, manifest v2 с SHA и строгими DLL-проверками.
   v1/generation JSONLv1 сохранены. Python конечному приложению не нужен.
5. Добавлены hybrid RRF, сохранение канонических привязок, соседних условий,
   corpus identity/version и запрет продолжать историю после смены базы.
6. Добавлены нормативный режим, строгие потоковые ссылки, уведомление о неполном
   ответе, адаптивный объём вместо обязательных 2–4 предложений.
7. UI показывает короткие ссылки и компактные предупреждения; полные исходные
   идентификаторы/предупреждения сохранены в карточке источника/истории.
8. Действительно обучены два полных E5 FT, повтор ordinary с другим seed,
   два 4B QLoRA и partial FT последних четырёх блоков 0.8B. Старые адаптеры не
   использовались. Checkpoints содержат optimizer/scheduler/RNG.
9. Проверены native E5 tokenizer100 и численный embedding parity; BGE tokenizer100
   и logit parity при длине256 также PASS, max logit error0.
10. Целевой native lifecycle PASS: отмена → следующий запрос; malformed JSON во
    время BUSY → controlled exit2. Найденный lifetime bug исправлен.
11. Generation220, prompt projection22, policy/stream/citations, manifest10,
    full-composition identity15, UI68 и реальный persistent Windows Job11 — PASS.
    См. handoff: последние Java изменения ещё включить в окончательную сборку.

## Зафиксированные данные и решения

Канонический корпус: 599 документов, 106139 фрагментов, 217 partial.
SHA SQLite: `58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a`.
Проверенный native pool: 564 фрагмента, 40 документов/36 семейств.
Train91 намерение (61RU/30KZ,17семейств), development120;
selection80 содержит 72 вопроса одного семейства, поэтому выводы узкие.
Final100 (67RU/33KZ,8семейств), из них30 transfer. 96 случаев — извлечение
положений,4 — явные условия; это не полноценный широкий semantic benchmark.

**SEALED FINAL НЕ ОТКРЫВАЛСЯ.** Папка `data/sealed-final`, SHA набора
`75ba09584976432e67d8ef8f61f4811de1abae6f00909b83a01546efb60b3f75`.
Не читать его до фиксации весов, индекса, промпта, кода и порогов.
Пять демонстрационных тем исключены из обучения/подбора; документы в индексе.

**Принят как перспективный компонент:** ordinary E5 seed1 + hybrid RRF.
На одинаковом native pipeline untrained→trained: Recall@10 .400→.975,
nDCG .275762→.872222. CI95 для ΔnDCG [.059176;.640450] положителен;
для ΔRecall [0;.618182] включает0. Второй seed повторил направление эффекта.
Это вклад весов в поиск на узком наборе, НЕ общая точность ответов.

**Отклонены:** BGE ordering8/12/24 (нет устойчивого выигрыша), bounded-context
логистический классификатор (теряет покрытие), BGE hard gate top3 с заранее
фиксированными 0/−2.5. Native gate140: selection74 answer/5clarify/1insufficient,
то есть потеря7.5п.п. > бюджета3п.п.; calibration19/20, gate20/20;
stress19insufficient+1nohits, но семантические отрицательные метки UNKNOWN.
Пороги после результата не меняли. Рабочий кандидат БЕЗ BGE ordering И hard gate.
Код optional возможностей/исследовательские веса сохранены.

## Генераторы и кандидаты

Исходная 4B Q6_K SHA:
`3acaea00a98af4e8cb7195259d7dd903b0cc749dff61a8cf47274c9a91ed02f6`.
Локальная A Q6_K: `generator/A/matched-baseline-Q6_K.gguf`, SHA
`f31c4e102d1a691bc91e5e390c99ef3d07600ac948f3329b05a7569eaef8d369`.
Одинаковы container/header/tokenizer и426tensor inventory; 178frozen tensors
byte-identical, заменены248learned Q6 matrices,744native block samples сверены.
НЕ ПРИМЕНЯТЬ неудачный `.partial.gguf` или `--finalize-existing` к нему:
он содержит foundation без LoRA и был отклонён guard.

4B A — кандидат полного RAG, ещё не окончательный победитель. A2/stress улучшили
буквальное воспроизведение/ссылки, но15/16 вопросов одного семейства; семантическая
полнота неизвестна. 9B общего преимущества не показала. B только немного лучше
NLL; её merge не запускать без основания. 0.8B literal coverage не выросла.

Full-RAG20 зафиксирован ДО ответов:
`generator/dev-rag20/input.jsonl`, SHA
`4ae917959e2bd4518bd983c5d71310622cababbeae33dacbdca9275a8a48c1c1`.
20 случаев =8minority +6RU/6KZdominant, итого14RU/6KZ и5семейств.
**Реальные парные RAG20 ещё НЕ запускались.**

Свежие model packages успешно подготовлены (по48hashedfiles):

- `candidate-hybrid-base/model`, manifest SHA `02538af6b25d3450a38686f2db51a5f40773cb90a498d0445174ce3a5fd0073a`.
- `candidate-hybrid-A/model`, manifest SHA `67acb0b840990f1f8941212b41101e406e7272f64117e773ebb0b767589d023a`.

Оба: ordinary E5, БЕЗ BGE, ctx16384, CUDA budget8GiB/reserve3GiB,
Job max8GiB/minfreeRAM9GiB. **В корнях этих runtime ещё нужно подготовить
native/ и readonly db/ (как в rag-runtime-v1), затем проверить readiness.**
Не путать с отклонённым `candidate-base`, где BGE hard gate для исследования.

Финальный worker source:
`gpu-worker/persistent-package/model`, EXE SHA
`60b57752154f6f1b8104c1d8880c9bc6551dda072db59b5dcce6ff6bd84e4ff8`.
Включены Haswell/x64, точные llama b11669/CUDA12.4 DLLs и лицензии.
Последний SHA менял только evidence maxLength256; generation Job11 относится
generation-identical471294…; benchmark hashes сохранены отдельно.

## Следующие действия после возобновления

1. Прочесть три handoff. Проверить фактический лимит субагентов; распределить
   независимые задачи до6, без одновременных правок одного файла. GPU тяжёлые
   запуски только последовательно через `gpu.lock`.
2. Скомпилировать текущий полный Java состав (последние WorkerManifest CPU-DLL
   compatibility и InferenceModule pipe-close fixes ещё включить в build).
3. Подготовить runtime roots candidate-hybrid-base/A, запустить paired RAG20
   через `src/research/java/ai/enterprise/research/RagEvaluation.java`, проверить
   точное ordered equality фактических контекстов, ошибки, ссылки, атомы,
   полноту/unknown. Scorer в `src/generator-research/dev_rag.py`.
4. Выбрать 4B base/A по реальным результатам. Сохранить проигравшие. Если
   преимущество генераторного FT не доказано — оставить исходный генератор.
5. Исправить ПОЛНОТУ переносимого комплекта источников до публикации.
   ВАЖНАЯ незавершённая находка: обычный pathlib увидел19missing originals/599
   и отсутствие корневого db/artifacts; clean-install имеет artifacts, но27
   original paths тоже не найдены. Это только наблюдение путей, ещё проверить
   Unicode/long paths/архивы/реальный resolver. Не менять канонические данные
   наугад и не публиковать текущий root вслепую.
6. Зафиксировать finalist (коды/веса/index/prompt/параметры/SHA), только затем
   открыть final100 и выполнить честную автоматическую оценку с ограничениями.
   Для F нужны контрольные замены обученных моделей исходными; не подгонять
   решение по финальному тесту. После исправлений по final нужен новый тест.
7. Окончательно собрать полный native app, проверить реальные цитаты/отмену/
   следующий запрос/контекст/источники. Teams можно снова запустить: обучение
   закончено. Измерить RAM/VRAM, latency с Teams без фоновых тяжёлых задач;
   отсутствие настоящего звонка прямо отметить, если он не проведён.
8. Пять final вопросов в новом диалоге каждый, настоящий максимизированный UI,
   тот же масштаб; сохранить полные ответы/источники/raw и читаемые crops.
   Не переделывать/не дорисовывать baseline. Длинные ответы — последовательные кадры.
9. Завершить Desktop MD/приложения/графики/воспроизведение. Только фактические
   результаты, без длительности проекта/обучения.
10. Обновить root комплект с возможностью rollback; сохранить исходные
    пользовательские Markdown/UI правки, `MODEL-INTERIM.md` и `model/instructions.md`.
11. Пользователь ранее явно просил публикацию обновлённого приложения и нужных
    файлов на GitHub. `gh auth status` успешно: MarkZamore, origin
    `https://github.com/MarkZamore/Mini-AI.git`. Публикации/commit/push ещё НЕ было.
    Подготовить конкретный проверенный portable release существующим pipeline,
    не включать devtools/старые веса/внутренние логи. Аудит release в handoff
    architecture. На C оставлять≥20GiB; архивы при необходимости на D.

## Среда и команды

Python: `src/build/model-preparation/venv/Scripts/python.exe` (использовать `-X utf8`).
Java25: `src/build/tools/graalvm-community-openjdk-25.0.2+10.1/bin/`.
CLI harness classpath:
`src/build/research-20261024/java;src/build/classes/java/main;src/build/resources/main;src/build/runtime-libs/*`.
Harness modes: `root input.jsonl output.jsonl search|answer|dense [limit]`;
результатный файл должен быть новым. App modules все7 через GeneratedCatalog.
`src/build.ps1 -Action build -Profile native` — полный build;
НЕ запускать общий test/run.ps1 с запрещёнными graph/module-removal проверками.

Для Windows UI читать skill computer-use и применять только node_repl + @oai/sky;
старых переменных sky/window после перезапуска нет. Сначала inventory, затем
один ввод → fresh state. Из-за occlusion/анимации проверять настоящий кадр Mini-AI
перед сохранением; несколько ранних неправильных снимков были заменены корректными.

На момент паузы C свободно около28.9GiB, D17.2GiB. Большие веса на Desktop
не дублировались. Конфигурация Codex уже содержит лимит6; текущая сессия
фактически позволяла только3субагента. После нового запуска проверить реальную
доступность, не обещать обход лимита среды.
