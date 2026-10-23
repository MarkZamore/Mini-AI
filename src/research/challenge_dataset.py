"""Freeze an additional source-grounded challenge without opening sealed-final.

This is an authored evaluation dataset, not training data and not an automated
semantic grader. Exact source checks establish provenance; semantic judgements
still need explicit review. Existing challenge output is never silently rebuilt.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / 'src/build/research-20261024'
DEFAULT_OUT = RESEARCH / 'challenge-v1'
CORPUS = RESEARCH / 'portable-corpus-v2'
DATA = RESEARCH / 'data'
EXPECTED_CORPUS_SHA = '58d6d60adbe58e4e9fe17ba0e843d40bc56906bd1dde9a7b2d12276401d7399a'
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
DEMO = re.compile(r'мобильност|практик|коррупц|управлен.{0,20}риск|тәуекел|ұтқыр|сыбайлас', re.I)


def norm(text):
    return ' '.join(text.split())


def sha(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def object_sha(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def read_rows(path):
    with path.open(encoding='utf-8-sig') as handle:
        return [json.loads(line) for line in handle if line.strip()]


def write_rows(path, rows):
    with path.open('xb') as handle:
        for row in rows:
            handle.write(json_bytes(row))


def write_json(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as handle:
        handle.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def long_path(path):
    resolved = str(path.resolve())
    return '\\\\?\\' + resolved if os.name == 'nt' and not resolved.startswith('\\\\?\\') else resolved


def xml_text(node):
    return norm(''.join((item.text or '') if item.tag.endswith('}t') else ' ' if item.tag.rsplit('}', 1)[-1] in ('tab', 'br', 'cr') else '' for item in node.iter()))


def resolve_id(prefix, sources):
    matches = [item for key, item in sources.items() if key.startswith(prefix)]
    if len(matches) != 1:
        raise ValueError(f'Fragment prefix must be unique: {prefix!r}: {len(matches)}')
    return matches[0]['fragment_id']


def fact(text, prefix, quote, kind='fact', critical=True):
    return {'text': text, 'prefix': prefix, 'quote': quote, 'kind': kind, 'critical': critical}


def own_cases():
    return [
        dict(name='teacher-hourly-exclusion', language='ru', category='exception',
             tags=['exception', 'units', 'scope'],
             question='Как внести почасовую преподавательскую нагрузку в индивидуальный план: включать её в семестровый и общий итог?',
             facts=[fact('Если есть почасовая нагрузка, её записывают в часах.', '3de492a8d46ce2f2', 'Если есть почасовая нагрузка заполняем в часах', 'condition'),
                    fact('Почасовую нагрузку исключают из общей нагрузки по семестрам и из итоговой.', '3de492a8d46ce2f2', 'не включаем в общую нагрузку по семестрам и в итоговую', 'exception')],
             forbidden=[('action', 'Почасовую нагрузку нужно включить в семестровый и общий итог.'), ('action', 'Почасовую нагрузку записывают только в кредитах.')]),
        dict(name='teacher-plan-workflow', language='ru', category='procedure',
             tags=['procedure', 'actors', 'ordering', 'multiple_sources'],
             question='Кто составляет индивидуальный план преподавателя, когда его заполняют и через какие согласование и утверждение он проходит?',
             facts=[fact('План составляет преподаватель.', '630299bd5db0c766', 'План составляется преподавателем'),
                    fact('План обсуждают на заседании кафедры.', '630299bd5db0c766', 'Обсуждается на заседании кафедры'),
                    fact('План утверждает директор института.', '630299bd5db0c766', 'утверждается директором института'),
                    fact('Заполнение происходит после определения всего объёма педагогической нагрузки на учебный год.', 'f0bdb06f30d634ef', 'после определения всего объема его педагогической нагрузки на учебный год', 'condition')],
             forbidden=[('actor', 'План утверждает только заведующий кафедрой.'), ('action', 'План заполняют до определения годового объёма нагрузки.')]),
        dict(name='teacher-plan-review-periods', language='ru', category='deadline',
             tags=['deadline', 'actors', 'frequency'],
             question='Когда и где должны рассматривать итоги выполнения индивидуального плана преподавателя?',
             facts=[fact('Итоги рассматривают два раза в год.', '72b2ed5d0194d994', 'рассматриваются два раза в год'),
                    fact('Периоды — после осеннего семестра и по окончании учебного года.', '72b2ed5d0194d994', 'после осеннего семестра и по окончании учебного года'),
                    fact('Рассмотрение проходит на заседаниях кафедры.', '72b2ed5d0194d994', 'на заседаниях кафедры')],
             forbidden=[('deadline', 'Итоги рассматриваются ежемесячно или только один раз в год.'), ('actor', 'Итоги рассматривает исключительно ректор без кафедры.')]),
        dict(name='teacher-responsibility-vs-supervision', language='ru', category='actors',
             tags=['actors', 'multiple_sources', 'responsibility'],
             question='Кто отвечает за своевременное оформление и выполнение индивидуального плана преподавателя, а кто должен его проверять?',
             facts=[fact('Ответственность за своевременное составление, оформление и выполнение несут преподаватель и заведующий кафедрой.', 'db4925d812010d68', 'Ответственность за своевременное составление, оформление и выполнение индивидуального плана несут преподаватель и заведующий кафедрой'),
                    fact('Систематическую проверку проводят должностные лица ректората.', '59f71b5b1db0b347', 'систематической проверке должностными лицами ректората')],
             forbidden=[('actor', 'Ответственность несёт только преподаватель.'), ('actor', 'За оформление отвечает только ректорат.')]),
        dict(name='syllabus-gadget-exam-exception', language='ru', category='exception',
             tags=['exception', 'scope', 'prohibition'],
             question='Как различаются правила использования электронных устройств на занятиях и на экзамене в имеющемся силлабусе?',
             facts=[fact('В указанном силлабусе использование электронных гаджетов на занятии названо обязательным и приветствуется.', '6eed21e0aff88070', 'обязательное использование электронных гаджетов на занятии, что приветствуется'),
                    fact('На экзамене использование гаджетов недопустимо.', '6eed21e0aff88070', 'недопустимо использование на экзамене', 'exception')],
             forbidden=[('action', 'Разрешение на занятия автоматически распространяется на экзамен.'), ('action', 'По этому фрагменту преподаватель уже дал персональное разрешение на экзамен.')]),
        dict(name='syllabus-literature-conditions', language='ru', category='multiple_sources',
             tags=['conditions', 'multiple_sources', 'deadline', 'scope'],
             question='Какие требования предъявляются к давности основной литературы и её доступности при составлении силлабуса?',
             facts=[fact('Основная литература указывается за последние десять лет.', 'bb5fa50b202608e9', 'Основная литература должна указываться за последние 10 лет', 'condition'),
                    fact('Указанная литература должна быть доступна в научной библиотеке университета.', 'ce358fec4e417b2a', 'литература должна быть доступна в научной библиотеке университета', 'condition')],
             forbidden=[('deadline', 'Основную литературу можно брать только за последние пять лет.'), ('action', 'Доступность литературы в библиотеке не требуется.')]),
        dict(name='syllabus-attendance-not-automatic-grade', language='ru', category='condition',
             tags=['condition', 'exception', 'actions'],
             question='Достаточно ли просто посещать занятия, чтобы автоматически повысить итоговую оценку по курсу?',
             facts=[fact('Посещение само по себе ещё не означает увеличения баллов.', '5f475b9f6f493c6f', 'посещение занятий само по себе еще не означает увеличение баллов', 'exception'),
                    fact('Нужно постоянное активное участие на занятиях.', '5f475b9f6f493c6f', 'Необходимо Ваше постоянное активное участие на занятиях', 'condition'),
                    fact('Обязательна подготовка к каждому занятию.', '5f475b9f6f493c6f', 'Обязательным требованием курса является подготовка к каждому занятию', 'condition')],
             forbidden=[('action', 'Любое посещение автоматически добавляет баллы.'), ('action', 'При посещении подготовка к занятиям необязательна.')]),
        dict(name='syllabus-falsification-consequence', language='ru', category='critical_action',
             tags=['condition', 'consequence', 'prohibition'],
             question='Что предусмотрено за фальсификацию информации курса и разрешено ли сдавать экзамен за другого студента?',
             facts=[fact('Сдавать экзамен за другого студента недопустимо.', '7b286af7b3b4aab5', 'Недопустимы подсказывание и списывание во время экзаменов, сдача экзамена за другого студента'),
                    fact('Уличенный в фальсификации информации курса получает итоговую оценку F.', '7b286af7b3b4aab5', 'Студент, уличенный в фальсификации любой информации курса, получит итоговую оценку «F»', 'condition')],
             forbidden=[('action', 'Экзамен за другого разрешается с его согласия.'), ('action', 'Фальсификация ведёт только к устному замечанию, без оценки F.')]),
        dict(name='confidentiality-post-employment-duration', language='kz', category='deadline',
             tags=['deadline', 'condition', 'calendar_units'],
             question='Құпиялылық туралы келісім бойынша жұмыскердің міндеттері еңбек шарты қолданылғанда және ол бұзылғаннан кейін қанша уақыт сақталады?',
             facts=[fact('Жұмыскердің міндеттері еңбек шартының қолданылу мерзімінде сақталады.', '74e36d8b061e9c91', 'Жұмыскер Еңбек шартының қолданылу мерзімінде', 'condition'),
                    fact('Жұмыскердің осы міндеттері шарт бұзылғаннан кейін бес күнтізбелік жыл ішінде сақталады.', '74e36d8b061e9c91', 'оны бұзғаннан кейін 5 (бес) кунтізбелік жыл ішінде')],
             forbidden=[('deadline', 'Міндеттер шарт бұзылғаннан кейін бес ай ғана сақталады.'), ('deadline', 'Міндеттер жұмыстан шыққан күні бірден тоқтайды.')],
             prior_diagnostic_topic_overlap=True),
        dict(name='confidentiality-court-disclosure-notice', language='kz', category='procedure',
             tags=['procedure', 'condition', 'actors', 'deadline'],
             question='Сот немесе құзыретті орган менен коммерциялық құпияны ашуды талап етсе, келісім бойынша алдымен не істеуім керек?',
             facts=[fact('Сот не құзыретті орган құпияны ашуды талап еткенде алдын ала жұмыс берушіге хабарлау қажет.', '4cb0006687665cf4', 'алдын ала хабардар етуге міндетті', 'condition'),
                    fact('Хабарлау құпияны ашуға қарсылық білдіру немесе оны қорғау шараларын қабылдау мүмкіндігі үшін керек.', '4cb0006687665cf4', 'коммерциялық құпияны ашуға карсылық білдіруге және/немесе коммерциялық құпияны қорғауға қажетті шаралар қабылдауға')],
             forbidden=[('action', 'Сот талап етсе, жұмыс берушіге мүлдем хабарлаудың қажеті жоқ.'), ('action', 'Сот талабын кез келген жағдайда орындауға мәңгі тыйым салынған.')]),
        dict(name='confidentiality-third-party-attempt', language='kz', category='actors',
             tags=['condition', 'actors', 'deadline', 'action'],
             question='Басқа адамдар менен ұйымның қызметтік немесе коммерциялық құпиясын алуға тырысса, кімге және қашан айтуым керек?',
             facts=[fact('Басқа адамдар құпия мәліметтерді алуға тырысқанда басшылыққа хабарлау керек.', '4f0b424c202cbcbe', 'бұл факт туралы басшылыққа', 'condition'),
                    fact('Хабарлау дереу жасалады.', '4f0b424c202cbcbe', 'дереу хабарлау')],
             forbidden=[('actor', 'Тек сыртқы адамға хабарлау жеткілікті, басшылыққа айту қажет емес.'), ('deadline', 'Хабарлауды келесі айдың соңына дейін кейінге қалдыруға болады.')]),
        dict(name='department-report-problems-and-comparison', language='ru', category='multiple_sources',
             tags=['multiple_sources', 'procedure', 'completeness'],
             question='В отчёте департамента достаточно перечислить выполненные мероприятия, или нужно ещё описать проблемы, принятые меры и сравнение с прошлыми периодами?',
             facts=[fact('Нужен анализ выполнения запланированных мероприятий за отчётный период.', 'de4e411c5edfccc6', 'анализ выполнения мероприятий, запланированных на отчетный период'),
                    fact('Указываются возникавшие проблемы и меры их решения.', 'de4e411c5edfccc6', 'с указанием возникавших проблем и принятых мер по их решению'),
                    fact('Достигнутые результаты представляются в сравнении с данными прошлых периодов.', '99a19c339ac653d0', 'результаты должны быть представлены в сравнении с данными прошлых периодов')],
             forbidden=[('action', 'Достаточно списка мероприятий, проблемы и прошлые результаты не нужны.')]),
        dict(name='department-report-next-year-and-resources', language='ru', category='procedure',
             tags=['multiple_sources', 'procedure', 'scope'],
             question='Что отразить в выводах отчёта департамента для следующего учебного года и предложений по улучшению работы?',
             facts=[fact('Нужно оценить результативность подразделения за отчётный период.', '2a903d101225b131', 'оценка результативности деятельности подразделения за отчетный период'),
                    fact('Ставятся задачи на следующий учебный год.', '2a903d101225b131', 'ставятся задачи на следующий учебный год'),
                    fact('Предложения и рекомендации по улучшениям включают потребность в ресурсах.', '6af0a7480ffb5225', 'предложения и рекомендации по улучшениям деятельности подразделения, включая потребность в ресурсах')],
             forbidden=[('action', 'Выводы ограничиваются прошлым годом, будущие задачи не отражают.')]),
        dict(name='bounded-readiness-threshold-absent', language='ru', category='insufficient',
             scope='bounded_context', expected_mode='insufficient', prefixes=['35b44a6f0a69ad03'],
             question_tail='Какой минимальный процент готовности обязательно нужен для допуска аудитории? Укажите число только если оно содержится в приведённом фрагменте.',
             required_missing_information=['Минимальный нормативный порог готовности или условие допуска в процитированном фрагменте не задан.'],
             forbidden=[('action', 'Минимальный порог допуска составляет90% или100%.'), ('action', 'Во всей базе отсутствуют нормативы готовности.')]),
        dict(name='bounded-confidentiality-storage-no-salary', language='ru', category='insufficient',
             scope='bounded_context', expected_mode='insufficient', prefixes=['73fe88527291bd93'],
             question_tail='Какова моя точная заработная плата в тенге? Можно ли её установить только из приведённого фрагмента?',
             required_missing_information=['Сумма зарплаты конкретного работника не содержится в предоставленном тексте об условиях хранения.'],
             forbidden=[('action', 'Названа любая конкретная сумма зарплаты как подтверждённая этим фрагментом.'), ('action', 'Во всём корпусе нет никаких сведений о зарплате.')]),
        dict(name='bounded-teacher-plan-no-exact-date', language='kz', category='insufficient',
             scope='bounded_context', expected_mode='insufficient', prefixes=['db4925d812010d68'],
             question_tail='Менің жеке жоспарымды тапсырудың нақты күнін күн-ай-жыл түрінде тек осы үзіндіден анықтауға бола ма?',
             required_missing_information=['Үзінді жауапты тұлғаларды атайды, бірақ тапсырудың күн-ай-жыл түріндегі нақты мерзімін бермейді.'],
             forbidden=[('deadline', 'Кез келген нақты күнді құжатта расталған мерзім ретінде атау.'), ('action', 'Бүкіл дерекқорда ешқандай мерзім жоқ деп мәлімдеу.')]),
        dict(name='bounded-gadget-no-exam-date', language='kz', category='insufficient',
             scope='bounded_context', expected_mode='insufficient', prefixes=['6eed21e0aff88070'],
             question_tail='Осы үзіндіге ғана сүйеніп, менің емтиханымды қайта тапсырудың нақты күнін атауға бола ма?',
             required_missing_information=['Гаджеттерді пайдалану жөніндегі үзінді емтиханды қайта тапсырудың нақты күнін көрсетпейді.'],
             forbidden=[('deadline', 'Қайта тапсырудың ойдан шығарылған нақты күнін атау.'), ('action', 'Бүкіл базада қайта тапсыру туралы ақпарат мүлдем жоқ деп мәлімдеу.')]),
        dict(name='ambiguous-transfer-object', language='ru', category='ambiguity',
             expected_mode='clarify', question='Можно перенести?',
             required_clarifications=['Уточнить, что именно пользователь хочет перенести и в какой ситуации.'],
             forbidden=[('action', 'Без уточнения дано конкретное разрешение или запрет на перенос экзамена, отпуска или нагрузки.')]),
        dict(name='ambiguous-deadline-object', language='kz', category='ambiguity',
             expected_mode='clarify', question='Қандай мерзім бар?',
             required_clarifications=['Қай әрекет, жағдай немесе құжат бойынша мерзім сұралғанын нақтылау.'],
             forbidden=[('deadline', 'Сұрақтың нысанын нақтыламай нақты мерзім белгілеу.')]),
    ]


def imported_cases(path, sources):
    candidates = json.loads(path.read_text(encoding='utf-8'))
    result = []
    categories = ['procedure', 'deadline', 'condition', 'condition', 'procedure', 'procedure', 'procedure', 'condition', 'procedure', 'procedure', 'exception', 'scope', 'condition', 'procedure', 'fact']
    for index, candidate in enumerate(candidates):
        facts = []
        for atom in candidate['required_atoms']:
            role = atom['role']
            kind = 'exception' if role in ('exception', 'scope', 'minimum_one_part') else 'condition' if 'condition' in role or role in ('trigger',) else 'fact'
            facts.append(fact(atom['claim'], atom['fragment_id'], atom['support_quote'], kind))
        kind_overrides = {('source-audit-01', 3): 'action', ('source-audit-05', 2): 'action',
                          ('source-audit-07', 3): 'actor', ('source-audit-08', 3): 'deadline',
                          ('source-audit-09', 2): 'actor', ('source-audit-10', 1): 'deadline',
                          ('source-audit-10', 3): 'action'}
        forbidden = []
        for number, text in enumerate(candidate['forbidden_assertions'], 1):
            kind = kind_overrides.get((candidate['candidate_id'], number),
                                      'deadline' if re.search(r'дн|день|срок|апта|күн|айы|дата|время', text, re.I) else 'action')
            forbidden.append((kind, text))
        result.append(dict(name=candidate['candidate_id'], language=candidate['language'], category=categories[index],
                           tags=sorted({atom['role'] for atom in candidate['required_atoms']}),
                           question=candidate['question'], facts=facts,
                           forbidden=forbidden,
                           limitations=candidate['scope_limitations'], origin='additional_automatic_source_auditor'))
    return result


def materialize(spec, sources, train_families):
    mode = spec.get('expected_mode', 'answer')
    scope = spec.get('scope', 'full_rag')
    facts = []
    used = set(resolve_id(prefix, sources) for prefix in spec.get('prefixes', []))
    for index, item in enumerate(spec.get('facts', []), 1):
        fid = resolve_id(item['prefix'], sources)
        if norm(item['quote']) not in norm(sources[fid]['text']):
            raise ValueError(f'Unsupported gold quotation: {spec["name"]}/{item["quote"]}')
        used.add(fid)
        facts.append(dict(id=f'f{index:02}', text=item['text'], evidence_ids=[fid], kind=item['kind'], critical=item['critical'],
                          support_quotes=[{'fragment_id': fid, 'text': item['quote']}],
                          rubric='Evaluate meaning including actor, action, modality, units, conditions and exceptions; exact wording is not required.'))
    source_ids = sorted(used)
    source_rows = [sources[fid] for fid in source_ids]
    family_ids = sorted({source['family_id'] for source in source_rows})
    if set(family_ids) & train_families:
        raise ValueError(f'Train family in challenge: {spec["name"]}')
    for source in source_rows:
        if source['demo_topic_quarantine']:
            raise ValueError(f'Quarantined demonstration source in challenge: {source["fragment_id"]}')
    if scope == 'bounded_context':
        intro = 'Отвечайте только по приведённому фрагменту, не делайте вывода об отсутствии ответа во всей базе.' if spec['language'] == 'ru' else 'Тек берілген үзіндіге сүйеніңіз. Бүкіл дерекқорда жауап жоқ деген қорытынды жасамаңыз.'
        supplied = '\n\n'.join(f'Фрагмент {i + 1}: «{source["text"]}»' for i, source in enumerate(source_rows))
        question = f'{intro}\n\n{supplied}\n\n{spec["question_tail"]}'
    else:
        question = spec['question']
    if DEMO.search(question):
        raise ValueError(f'Question overlaps excluded demonstration topic: {spec["name"]}')
    limits = [
        'Automatic authored source-grounded rubric, not independent human judgement.',
        'Full legal actuality, every external exception and applicability to an individual are not independently established.',
        'Known source clauses define the evaluated scope; equivalent truthful paraphrases may satisfy facts after review.',
        'Created after earlier development research, frozen before challenge model answers; not historically blind.',
        'Some source families may overlap the sealed100 families, but sealed100 questions/answers were never opened by this builder.',
    ] + spec.get('limitations', [])
    if scope == 'bounded_context':
        limits.append('Insufficiency applies only to explicitly supplied fragments; corpus-wide answerability is unknown and not claimed.')
    raw = dict(schema_version=1, name=spec['name'], question=question, language=spec['language'], category=spec['category'],
               tags=spec.get('tags', [spec['category']]), family_ids=family_ids, document_ids=sorted({s['document_id'] for s in source_rows}),
               intent_group=hashlib.sha256(('challenge-v1/' + spec['name']).encode()).hexdigest(),
               scope=scope, source_ids=source_ids, required_facts=facts,
               forbidden_assertions=[dict(id=f'x{i:02}', kind=kind, text=text, reason='Unsupported or contradicted actor/action/deadline in the scoped source rubric; review the actual asserted meaning, not a quoted or negated string.', evidence_ids=source_ids) for i, (kind, text) in enumerate(spec.get('forbidden', []), 1)],
               expected_mode=mode, context_ids=source_ids if scope == 'bounded_context' else [],
               review_status='automatic_source_verified' if source_ids else 'not_applicable_meta_clarification',
               required_clarifications=spec.get('required_clarifications', []),
               required_missing_information=spec.get('required_missing_information', []),
               human_verified=False, training_families_excluded=True,
               source_scope='Identified native document clauses, not a universal statement about all current law.',
               corpus_wide_answerability='unknown_not_claimed' if mode == 'insufficient' else 'known_identified_source_answer' if mode == 'answer' else 'query_under_specified',
               prior_diagnostic_topic_overlap=spec.get('prior_diagnostic_topic_overlap', False),
               authored_by=spec.get('origin', 'report_finish_automatic_source_author'), limitations=limits)
    raw['id'] = hashlib.sha256(('challenge-v1/' + spec['name'] + '\n' + question).encode()).hexdigest()
    raw['case_sha256'] = object_sha(raw)
    return raw


def verify_sources(source_ids, sources, corpus):
    database = corpus / 'db/index.sqlite'
    if sha(database) != EXPECTED_CORPUS_SHA:
        raise ValueError('Canonical corpus SHA changed')
    documents = {}
    for path in (corpus / 'db/documents').glob('*.json'):
        document = json.loads(path.read_text(encoding='utf-8-sig'))
        documents[document['document_id']] = document
    connection = sqlite3.connect(database.as_uri() + '?mode=ro&immutable=1', uri=True)
    connection.row_factory = sqlite3.Row
    verified = []
    file_cache = {}
    part_cache = {}
    try:
        for fid in sorted(source_ids):
            source = sources[fid]
            canonical = connection.execute('SELECT * FROM chunks WHERE fragment_id=?', (fid,)).fetchone()
            if canonical is None or canonical['document_id'] != source['document_id'] or norm(canonical['text']) != norm(source['text']):
                raise ValueError(f'Canonical chunk mismatch: {fid}')
            document = documents[source['document_id']]
            original = (corpus / 'db' / document['source']['path']).resolve()
            if not original.is_relative_to((corpus / 'docs').resolve()):
                raise ValueError('Source escapes verified docs root')
            if str(original) not in file_cache:
                file_cache[str(original)] = sha(Path(long_path(original)))
            original_sha = file_cache[str(original)]
            if original_sha != source['source_sha256'] or original_sha != document['source_version_sha256']:
                raise ValueError(f'Original SHA mismatch: {fid}')
            accepted = []
            for source_ref in source['source_refs']:
                if source_ref.get('artifact_id') != 'original' or source_ref.get('kind') != 'ooxml_node':
                    raise ValueError(f'Challenge only accepts verified original DOCX XML: {fid}')
                if source_ref.get('source_sha256') != original_sha:
                    raise ValueError('Source-ref version mismatch')
                part, xpath = source_ref['part'], source_ref['xpath']
                if not xpath.startswith('/w:document/'):
                    raise ValueError(f'Unsupported original XPath: {xpath}')
                cache_key = (str(original), part)
                if cache_key not in part_cache:
                    with zipfile.ZipFile(long_path(original)) as package:
                        part_cache[cache_key] = ET.fromstring(package.read(part))
                node = part_cache[cache_key].find('./' + xpath[len('/w:document/'):], NS)
                node_text = xml_text(node) if node is not None else None
                if node_text is None or norm(source['text']) not in node_text:
                    raise ValueError(f'Original XML mismatch: {fid}')
                accepted.append({'part': part, 'xpath': xpath, 'source_sha256': original_sha,
                                 'node_text_sha256': hashlib.sha256(node_text.encode('utf-8')).hexdigest(),
                                 'chunk_equals_node': norm(source['text']) == node_text,
                                 'chunk_substring_in_node': True})
            verified.append(dict(fragment_id=fid, document_id=source['document_id'], family_id=source['family_id'],
                                 title=source['title'], text=source['text'], original_path=str(original),
                                 original_sha256=original_sha, canonical_row_verified=True, original_xml_verified=True,
                                 source_refs=accepted, human_verified=False, semantic_support='authored_scoped_rubric_not_independent_human_gold'))
    finally:
        connection.close()
    return verified


def build(out, corpus, audit_path, revision=1, supersedes=None):
    out.mkdir(parents=True, exist_ok=True)
    outputs = ['cases.jsonl', 'questions.jsonl', 'source-verification.jsonl', 'manifest.json', 'README.md']
    if any((out / name).exists() for name in outputs):
        raise FileExistsError('Frozen challenge already exists; use verify, do not rebuild in place')
    sources = {row['fragment_id']: row for row in read_rows(DATA / 'sources.jsonl')}
    train = read_rows(DATA / 'train.jsonl')
    train_families = {fid for row in train for fid in row['family_ids']}
    specifications = imported_cases(audit_path, sources) + own_cases()
    cases = [materialize(spec, sources, train_families) for spec in specifications]
    if len(cases) < 24 or len({case['question'] for case in cases}) != len(cases):
        raise ValueError('Challenge must contain at least24 distinct questions')
    if len(cases) != 34:
        raise ValueError(f'Unexpected authored count: {len(cases)}')
    if len({case['id'] for case in cases}) != len(cases):
        raise ValueError('Duplicate challenge ID')
    source_ids = {fid for case in cases for fid in case['source_ids']}
    verified = verify_sources(source_ids, sources, corpus)
    questions = [{key: case[key] for key in ('id', 'question', 'language', 'scope', 'category', 'family_ids', 'intent_group', 'expected_mode', 'context_ids', 'case_sha256')} for case in cases]
    write_rows(out / 'cases.jsonl', cases)
    write_rows(out / 'questions.jsonl', questions)
    write_rows(out / 'source-verification.jsonl', verified)
    full_answer = [case for case in cases if case['scope'] == 'full_rag' and case['expected_mode'] == 'answer']
    manifest = dict(schema_version=1, dataset='challenge-v1', revision=revision, status='frozen_before_any_challenge_answers',
                    authored_cases=len(cases), language_counts=dict(Counter(case['language'] for case in cases)),
                    primary_category_counts=dict(Counter(case['category'] for case in cases)),
                    scope_mode_counts=dict(Counter(f'{case["scope"]}:{case["expected_mode"]}' for case in cases)),
                    full_rag_answer_cases=len(full_answer), verified_source_fragments=len(verified),
                    source_documents=len({row['document_id'] for row in verified}), source_families=len({row['family_id'] for row in verified}),
                    family_case_counts=dict(Counter(fid for case in cases for fid in case['family_ids'])),
                    training_families_excluded=True, train_family_count=len(train_families),
                    automatic_original_xml_sha_verification=True, human_review=False,
                    source_database_sha256=EXPECTED_CORPUS_SHA, source_dataset_sha256=sha(DATA / 'sources.jsonl'),
                    train_dataset_sha256=sha(DATA / 'train.jsonl'), author_code_sha256=sha(Path(__file__)),
                    source_audit_candidates_sha256=sha(audit_path),
                    artifacts={name: {'path': str(out / name), 'sha256': sha(out / name)} for name in ('cases.jsonl', 'questions.jsonl', 'source-verification.jsonl')},
                    challenge_model_answers_read=False, model_answers_used_for_authoring=False,
                    training_or_configuration_tuning_use_forbidden=True, sealed_final_read=False,
                    replaces_sealed100=False, historical_blind_test=False,
                    evaluation_policy='Run only after complete system freeze; main semantic quality requires explicit source-linked review; unknown is non-success; literal/citation diagnostics are separate.',
                    limitations=[
                        'Additional challenge authored after earlier development failures; some family/topic familiarity exists.',
                        'No independent human review; exact XML matching establishes provenance, not every normative implication.',
                        'Source families are excluded from weight training; independence from sealed100 families/intentions is unknown because sealed files were not opened.',
                        'Four bounded-context insufficiency cases do not establish corpus-wide absence; score separately.',
                        'Two deliberately ambiguous short queries are mode checks, not ordinary answerable fact questions.',
                        'Known source clauses include templates; current legal applicability and every external exception are not established.',
                    ])
    if supersedes is not None:
        previous = json.loads(supersedes.read_text(encoding='utf-8'))
        if not previous.get('challenge_model_answers_read') is False:
            raise ValueError('This pre-answer revision cannot supersede an already evaluated challenge')
        manifest['supersedes_manifest'] = {'path': str(supersedes), 'sha256': sha(supersedes)}
        manifest['pre_answer_revision_reason'] = 'Independent original-source audit corrected gadget obligation modality, employee-specific duration scope and explicit support for an examination prohibition. No model answers used.'
    write_json(out / 'manifest.json', manifest)
    readme = f'''# Дополнительный challenge-v1\n\nЗафиксировано {len(cases)} случая до ответов моделей. Это дополнительная содержательная проверка, не замена sealed100 и не данные для обучения/подбора.\n\n28 вопросов с ответом проверяют полный RAG; 4 случая недостаточности явно ограничены предоставленным текстом; 2 неоднозначных вопроса требуют уточнения. Главный показатель ответов, ошибки режимов и bounded-context результаты имеют разные знаменатели.\n\nВсе исходные семейства исключены из train. Проверены оригинальные DOCX SHA, XML part/xpath и канонические SQLite строки. Метки и рубрики созданы автоматическими агентами; человеческой экспертизы нет. Смысловая приёмка требует отдельного source-linked review, буквального совпадения недостаточно.\n\nСм. manifest.json для SHA вопросов, рубрик, проверенных источников, кода и входов. cases.jsonl содержит обязательные смысловые факты/условия/исключения, точные опорные цитаты и недопустимые утверждения. questions.jsonl — вход для настоящего полного приложения. source-verification.jsonl — доказательства происхождения.\n\nНе перестраивать набор поверх этих файлов после получения ответов. Изменения после результатов требуют новой версии набора и нового честного описания. Ни один файл sealed-final этой программой не читается.\n'''
    with (out / 'README.md').open('x', encoding='utf-8') as handle:
        handle.write(readme)
    print(json.dumps({key: manifest[key] for key in ('authored_cases', 'language_counts', 'scope_mode_counts', 'source_documents', 'source_families', 'verified_source_fragments')}, ensure_ascii=False))
    print('manifest_sha256=' + sha(out / 'manifest.json'))


def verify(out, corpus):
    manifest = json.loads((out / 'manifest.json').read_text(encoding='utf-8'))
    for name, item in manifest['artifacts'].items():
        if sha(out / name) != item['sha256']:
            raise ValueError(f'Frozen artifact changed: {name}')
    cases = read_rows(out / 'cases.jsonl')
    for case in cases:
        copy = dict(case)
        expected = copy.pop('case_sha256')
        if object_sha(copy) != expected:
            raise ValueError(f'Case content changed: {case["id"]}')
    if sha(DATA / 'train.jsonl') != manifest['train_dataset_sha256'] or sha(DATA / 'sources.jsonl') != manifest['source_dataset_sha256']:
        raise ValueError('Input datasets changed')
    sources = {row['fragment_id']: row for row in read_rows(DATA / 'sources.jsonl')}
    verified = verify_sources({fid for case in cases for fid in case['source_ids']}, sources, corpus)
    old = read_rows(out / 'source-verification.jsonl')
    if verified != old:
        raise ValueError('Original source verification changed')
    print(json.dumps({'status': 'PASS', 'cases': len(cases), 'sources_reverified': len(verified), 'sealed_final_read': False, 'manifest_sha256': sha(out / 'manifest.json')}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('build', 'verify'))
    parser.add_argument('--out', type=Path, default=DEFAULT_OUT)
    parser.add_argument('--corpus', type=Path, default=CORPUS)
    parser.add_argument('--source-audit', type=Path, default=DEFAULT_OUT / 'source-audit/candidates.json')
    parser.add_argument('--revision', type=int, default=1)
    parser.add_argument('--supersedes-manifest', type=Path)
    args = parser.parse_args()
    if args.command == 'build':
        if args.revision > 1 and args.supersedes_manifest is None:
            parser.error('A new revision must preserve and name its predecessor manifest')
        build(args.out.resolve(), args.corpus.resolve(), args.source_audit.resolve(), args.revision,
              args.supersedes_manifest.resolve() if args.supersedes_manifest else None)
    else:
        verify(args.out.resolve(), args.corpus.resolve())


if __name__ == '__main__':
    main()
