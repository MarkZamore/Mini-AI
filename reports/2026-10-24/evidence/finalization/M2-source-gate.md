# Ограниченный M2 source pilot: содержательный вход не найден

**TRAINREADY = 0. Вопросы, supervised targets и negative pools не созданы.** Модели, GPU и обучение не запускались. Это отрицательный результат проверки данных, а не неудача обучения.

До чтения полных оригиналов и авторства вопросов был зафиксирован `family-split-preregister-v1.json`, SHA **`dd5092bdb169d73b7e1128ba5e8c9d87c877ad421dd875e469e2a7155286f917`**: 9 train families / 13 paragraph candidates и 3 dev families / 4 candidates. Все семьи находятся вне actual old train17, старых final/development assignment groups, public reserved49, current open-dev5 и demonstration/identity quarantine. Разбиение не изменялось после изучения источников.

Для **12 оригинальных DOCX** проверены SHA по SQLite. Все **17** canonical fragments сверены с точным native OOXML part/XPath и полным текстом paragraph с нормализацией пробелов. Проверены также соседние paragraphs и сохранены source contexts. Ошибки извлечения этих строк не установлены.

Но их фактическое содержание:

| Вид | Количество | Решение |
|---|---:|---|
| `word/header*.xml`: название организации | **14** | Нет нормативного actor/action/condition; обучение ответа названием пересекалось бы с identity demonstration |
| `word/footer*.xml`: служебное название формы | **2** | Не правило и не целое основание для обычного вопроса |
| Body paragraph: линия отрыва | **1** | Нет букв/чисел и ответа по существу |

Таким образом, исходный metadata screening действительно нашёл полные XML paragraphs, но **не доказал их содержательную пригодность**. Он включал headers/footers и одну длинную пунктуационную строку. Число 17 нельзя превращать в число нормативных намерений. Старые 73 потенциальных source-quotation примера также не используются как готовый SFT gold.

**Не составлялись вопросы по цитатам/названию документа, не придумывались условия, не объявлялись чужие ID отрицательными.** Нельзя создавать такие targets ради прохождения минимального объёма. Split сохранён; набор не расширялся другими paragraphs, семьями, OCR или DOC conversions. Этот вывод относится к ограниченным 17 candidates; он не означает отсутствия любых полезных полей во всех этих документах.

Артефакты:

- `source-candidates-v1.json`, SHA **`19126835553e809e02ed7b81a524c79b3c1884c8bc785abb591f0dc1285918a4`** — все17 ID/тексты/refs, part roles, соседний XML/canonical context;
- `original-source-proofs-v1.json`, SHA **`53a024f7d8bffcdd4e00eca601a1a4f96bc0028d0575c31cdeabc2946cf56df3`** — original paths/SHA и прочитанные XML parts;
- `source-curation-proposal-v1.json`, SHA **`4a6dd6f2e3243d398ca815b3766a70a443e3ae1ffce377df4977445720293553`** — поимённые причины17 отказов, 0 questions/targets/pools, неизменный split.

Передано независимому автоматическому reviewer `/root/final_evaluation`; на момент этой записи его отдельное заключение ещё ожидается. **Независимая человеческая проверка не выполнялась.** Gold ledger не попадал в model inputs; новый system prompt/catalog не создавался. Search/indices/weights/production не менялись.
