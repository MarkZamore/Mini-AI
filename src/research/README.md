# Локальное исследование Mini-AI

Эти скрипты работают с неизменяемыми `docs`/`db` и сохраняют артефакты в `src/build/research-20261024`. Они не заменяют приложение или модель в корне проекта. Отдельные проверки графа и конфигурации с удалёнными модулями не используются.

## Данные и ограничения

`data_pipeline.py` повторно проверяет SHA256 оригинальных DOCX и равенство текста узлов исходному XML. Пул содержит 564 уникальных фрагмента: 370 RU, 194 KZ, 40 документов, 36 предварительно сгруппированных семейств. Исключаются заполнители форм, непроверенный OCR и консервативно целые демонстрационные темы. Разметка автоматическая; человеческой проверки нет.

Зафиксированный train: 91 намерение (61 RU/30 KZ), 17 семейств. Development: 80 selection (44 RU/36 KZ, пять семейств), 20 calibration и 20 gatecheck, всего восемь семейств. Final: 100 случаев (67 RU/33 KZ), восемь удержанных семейств, 30 случаев отдельной тематической группы. Семейства всех пяти подвыборок не пересекаются. Два семейства доминируют в final, а одно содержит 72 из 80 selection-вопросов: доверительные интервалы ограничены этой концентрацией.

Это автоматический документно обусловленный набор извлечения, включая явно присутствующие условия. Он **не является** независимо проверенным семантическим тестом всех процедур, исключений, отказов и конфликтов. Отсутствующие категории не заполняются вымышленными метками. `required_atoms` являются точными подстроками источников; семантическая полнота отмечена `unknown`.

`sealed-final` запрещено читать во время обучения и выбора. Генератор данных составляет final и потому не считается независимым слепым оценщиком. Первые черновики были заменены по причинам покрытия семейств до любых результатов моделей. Окончательный SHA указан в `data/manifest.json`; финальные ответы не использовались для настройки.

## Воспроизведение

Используется существующий `src/build/model-preparation/venv/Scripts/python.exe`, PyTorch/Transformers, ONNX и ONNX Runtime. Не запускайте одновременно несколько GPU-команд. CUDA-команды используют атомарный `gpu.lock` с PID и уникальным владельцем; освобождение выполняется в `finally` только владельцем.

```powershell
$python = 'src/build/model-preparation/venv/Scripts/python.exe'
& $python -B src/research/data_pipeline.py
& $python -B src/research/check_data.py
& $python -B src/research/e5_download.py
& $python -B src/research/e5_train.py prepare
& $python -B src/research/e5_train.py baseline --device cuda --allow-cuda
& $python -B src/research/e5_train.py train --variant ordinary --device cuda --allow-cuda --steps 2 --evaluate
& $python -B src/research/e5_train.py train --variant ordinary --device cuda --allow-cuda --steps 16 --resume --evaluate
& $python -B src/research/e5_train.py train --variant ordinary --device cuda --allow-cuda --steps 0 --resume --evaluate
```

Те же три команды выполнить для `--variant hard`. Каждая ветвь начинает с исходного E5, одна эпоха содержит 23 шага AdamW при накоплении четырёх microbatch. Обновляются все 117 653 760 параметров; learning rate 2e-5, weight decay .01, temperature .05, длина 256, seed 20261024. Варианты различаются обычным и лексически сложным отрицательным примером. Отрицательные документы относятся к другому семейству и не являются запрошенным документом; независимой смысловой разметки отрицательных пар нет.

Checkpoint содержит веса, оптимизатор, scheduler, порядок примеров, позицию, Python/NumPy/CPU/CUDA RNG. BF16 autocast не использует loss scaler. Зафиксированы промежуточные и итоговые состояния. `weight-update-proof.json` содержит контрольные суммы и реальные изменения базовых тензоров.

Исходная модель: `intfloat/multilingual-e5-small`, revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`. Скачиваются только публичные веса; корпус наружу не отправляется. Модель использует Bert encoder, но XLM-R/SentencePiece tokenizer. Обязательны префиксы `query: ` и `passage: `.

```powershell
& $python -B src/research/e5_compare.py
& $python -B src/research/e5_export.py --variant baseline
& $python -B src/research/e5_export.py --variant ordinary
& $python -B src/research/e5_export.py --variant hard
& $python -B src/research/generator_tasks.py
```

ONNX: три входа int64 `[batch,sequence]` (`input_ids`, `attention_mask`, `token_type_ids`), выход float32 `embedding[batch,384]`, mean pooling по маске и L2-нормализация. Проверяется паритет Torch/ORT на RU/KZ, разных batch/sequence и padding.

Первое сравнение использует пул 564 XML-подтверждённых фрагментов и служит только диагностикой. Решение о внедрении требует paired selection на всех 106 139 фрагментах исходного индекса. `e5_index.py` создаёт sidecar вне исходного `db`: `vectors.f32` little endian `[rows,384]`, `fragments.jsonl`, `index.json`. Для построения необходим файл регистрации кандидата с `e5_variant` и `final_opened:false`; это не финальное принятие модели. Затем требуется полный freeze генератора, поиска, промпта и порогов до раскрытия final.

`generator-context-variants.jsonl` содержит 203 учебных контекста на 91 намерение: 91 полный, 91 с лишними фрагментами, 21 явно усечённый. Это не 203 независимых вопроса. При усечении target ограничен предоставленным буквальным span и прямо сообщает о неподтверждённой полноте документа.

## Оценка ответов

`evaluate_answers.py` принимает JSONL `{id,text,context_ids,citation_aliases?,ttft_seconds?,full_response_seconds?}`. Совпадение буквальных атомов и ссылок — автоматический proxy. Перефразировки и лишние смысловые утверждения, не проверяемые этим способом, остаются `unknown`; они не превращаются в успех. Независимая семантическая оценка отсутствует, её консервативная нижняя граница отдельно равна нулю. Это ограничение измерения, а не доказательство ошибочности всех ответов.

Для `--split final` обязательны `--freeze` и поля `e5_variant`, `generator_sha256`, `prompt_sha256`, `corpus_sha256`, `thresholds`, `final_set_sha256`. Раскрытие журналируется. Ошибки final нельзя использовать для последующего подбора с сохранением статуса того же независимого теста.

## Дополнительные контрольные сравнения

После разрешённого GPU-окна ordinary повторён с seed 20261025 и `--run-name ordinary-seed2`, с теми же данными и гиперпараметрами. Порог допуска полного индекса закреплён заранее в `models/e5/ordinary-seed2-registration.json`. Seed1 остаётся заранее выбранным кандидатом; лучшая инициализация после результата не выбирается.

```powershell
& $python -B src/research/e5_full_evaluate.py --variant baseline
& $python -B src/research/e5_full_evaluate.py --variant ordinary
& $python -B src/research/e5_full_evaluate.py --variant ordinary-seed2
& $python -B src/research/e5_compare.py --pool full --seed2
```

Итоговый scorer использует CPU ONNX Runtime и стабильный порядок fragment ID при одинаковом score. Он заменяет первоначальное оптимистическое вычисление ранга по числу строго больших score; исходные результаты сохранены в файлах `before-stable-ORT-correction`. В обоих E5-вариантах исправление затронуло один ранг, Recall не изменился. Данные, веса и критерии принятия по результату исправления не менялись.

Frozen `BAAI/bge-reranker-v2-m3@953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e` экспортирован с исходным SentencePiece. ONNX больше 2 GB представлен внешними tensor sidecar: поставлять нужно всю папку с неизменными именами. Выход — raw relevance logit, не вероятность истинности. Варианты top8/top12 сравниваются преимущественно по сохранённым top24-score, для неоднозначного старого cache выполнено одно CPU-пересчитывание; новых GPU-экспериментов BGE нет.

```powershell
& $python -B src/research/bge_reranker.py download
& $python -B src/research/bge_reranker.py export
& $python -B src/research/bge_evaluate.py --variant ordinary --pool-size 24
& $python -B src/research/bge_subset_compare.py
& $python -B src/research/adequacy_stress.py --variant ordinary
& $python -B src/research/adequacy_stress.py --variant ordinary --gate
& $python -B src/research/bounded_evidence_pilot.py
```

Пороги relevance-кандидата 0/-2.5 закреплены до gate20. Внешние stress-темы не считаются доказанными `no-answer` во всём корпусе. Logistic-пилот обучен только на буквальной полноте предоставленного контекста; из-за потери покрытия положительных контекстов он не принят. Оба ограничения исключают заявление об обученной семантической достаточности.

Сохранённые native-запросы полного состава приложения оцениваются отдельно от dense-компонента. Контроль untrained hybrid сохраняет ту же RRF-композицию и меняет только E5/index; он отделяет вклад весов от инженерных изменений. Все эти команды читают development, не final.

```powershell
& $python -B src/research/native_pipeline_evaluate.py --untrained src/build/research-20261024/rag-runtime-untrained/selection.jsonl
& $python -B src/research/research_summary.py
```

`selection-decision.json` и `selection-summary.md` содержат рекомендации по компонентам, ссылки и SHA доказательных метаданных, все отклонения состава данных и статус unknown семантического качества. Они не разрешают раскрывать final и не заменяют фиксацию всей сборки родительским процессом.
