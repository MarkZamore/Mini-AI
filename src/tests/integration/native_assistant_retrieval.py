"""Assistant retrieval regression against the full current native application.

Run with Python -B and --exe PATH. The application, index and documents are read
only; the sole output is a JSON report in src/build/test-results. No alternative
module compositions or separate graph checks are exercised.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import re
import sqlite3
import subprocess
import sys
from pathlib import Path


MOBILITY_ID = 'satbayev:document:43156'
POLICY_ID = 'satbayev:document:28850'
PRACTICE_ID = 'satbayev:document:32374'
STUDY_RULES_ID = 'satbayev:document:40796'
MOBILITY_SECTION = '2 уровень - ДП КазНИТУ'
QUESTION = ('Найди мне нормативный документ касаемо академической мобильности, '
            'напиши условия и дай рекомендации')
POLICY_QUESTION = 'Найди мне документ об академической политике, напиши основные условия'
PRACTICE_QUESTION = 'Какие правила прохождения практики установлены?'


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exe', type=Path, required=True)
    parser.add_argument('--report-name', default='native-assistant-retrieval.json')
    args = parser.parse_args()
    if (len(args.report_name) > 128 or
            re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*\.json', args.report_name) is None):
        parser.error('--report-name must be an ASCII JSON basename')

    project = Path(__file__).resolve().parents[3]
    exe = args.exe.resolve(strict=True)
    if not exe.is_file():
        parser.error('--exe must name the complete current application EXE')
    output = project / 'src/build/test-results'
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / args.report_name
    with exe.open('rb') as stream:
        exe_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = {
        'exe': str(exe), 'exe_sha256': exe_sha,
        'started_utc': datetime.now(timezone.utc).isoformat(),
        'complete_current_application_only': True,
        'module_variants_tested': False, 'separate_graph_checks': False,
        'cwd_differs_from_executable': exe.parent != project / 'src/build',
        'cases': [], 'canonical_fragments_checked': 0, 'pass': False,
    }
    canonical = {}

    def command(*arguments):
        completed = subprocess.run(
            [str(exe), *arguments], cwd=project / 'src/build',
            capture_output=True, timeout=45,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
        )
        stdout = completed.stdout.decode('utf-8', errors='strict')
        stderr = completed.stderr.decode('utf-8', errors='strict')
        if completed.returncode:
            raise AssertionError(
                f'{arguments!r}: exit {completed.returncode}; '
                f'stdout={stdout!r}; stderr={stderr!r}')
        if stderr.strip():
            raise AssertionError(f'{arguments!r}: unexpected stderr {stderr!r}')
        try:
            return json.loads(stdout)
        except json.JSONDecodeError as error:
            raise AssertionError(f'{arguments!r}: non-JSON output {stdout!r}') from error

    def validate_hits(hits, limit):
        if not isinstance(hits, list) or len(hits) > limit:
            raise AssertionError(f'Expected at most {limit} fragments; received {hits!r}')
        seen = set()
        for hit in hits:
            if not isinstance(hit, dict):
                raise AssertionError(f'Expected fragment object, received {hit!r}')
            fid = hit.get('fragment_id', '')
            if re.fullmatch(r'[a-f0-9]{64}', fid) is None or fid in seen:
                raise AssertionError(f'Invalid or duplicate canonical fragment ID: {fid!r}')
            seen.add(fid)
            if not hit.get('source_refs'):
                raise AssertionError(f'Fragment {fid} has no exact source references')
            if 'score' not in hit:
                raise AssertionError(f'Fragment {fid} omits its search score')
            if fid not in canonical:
                canonical[fid] = command('--get-fragment', fid)
            received = dict(hit)
            received.pop('score')
            if received != canonical[fid]:
                reference = canonical[fid]
                keys = received.keys() | reference.keys() if isinstance(reference, dict) else set()
                changed = sorted(key for key in keys if received.get(key) != reference.get(key))
                raise AssertionError(f'Fragment {fid} differs from canonical get-fragment: {changed}')

    def case(name, query, *, limit=10, options=(), required_document=None,
             expected_empty=False, informative=False, allowed_documents=None,
             allowed_section=None, positional_limit=False,
             search_command='--assistant-search', text_contains=None, min_results=1):
        arguments = [search_command, query]
        arguments += [str(limit)] if positional_limit else ['--limit', str(limit)]
        hits = command(*arguments, *options)
        validate_hits(hits, limit)
        ids = {hit['document_id'] for hit in hits}
        if expected_empty and hits:
            raise AssertionError(f'{name}: nonexistent/excluded topic produced {len(hits)} hits')
        if not expected_empty and not hits:
            raise AssertionError(f'{name}: expected matching source fragments')
        if not expected_empty and len(hits) < min_results:
            raise AssertionError(f'{name}: expected at least {min_results} source fragments, received {len(hits)}')
        if required_document and required_document not in ids:
            raise AssertionError(f'{name}: primary document {required_document} missing; got {sorted(ids)}')
        if allowed_documents is not None and not ids <= set(allowed_documents):
            raise AssertionError(f'{name}: document filter escaped: {sorted(ids)}')
        if allowed_section is not None:
            unexpected = [did for did in ids if allowed_section not in sections.get(did, [])]
            if unexpected:
                raise AssertionError(f'{name}: section filter escaped: {unexpected}')
        if text_contains is not None and not any(
                text_contains.casefold() in hit.get('text', '').casefold() for hit in hits):
            raise AssertionError(f'{name}: source text does not identify {text_contains!r}')
        informative_hits = []
        if informative:
            informative_hits = [hit for hit in hits
                                if hit['document_id'] == MOBILITY_ID
                                and len(hit.get('text', '').strip()) > 80
                                and re.search(r'(?i)(?:gpa[\s\S]{0,35}2[,.]67|конкурс)', hit['text'])]
            if not informative_hits:
                raise AssertionError(f'{name}: primary sources contain no substantive eligibility/competition content')
        report['cases'].append({
            'name': name, 'query': query, 'command': search_command,
            'limit': limit, 'options': list(options),
            'result_count': len(hits), 'document_ids': sorted(ids),
            'fragment_ids': [hit['fragment_id'] for hit in hits],
            'substantive_primary_fragments': [hit['fragment_id'] for hit in informative_hits],
            'pass': True,
        })

    try:
        status = command('--status')
        expected_modules = {
            json.loads(path.read_text(encoding='utf-8'))['id']
            for path in (project / 'src/modules').glob('*/module.json')
        }
        missing_modules = expected_modules - set(status.get('modules', []))
        if missing_modules:
            raise AssertionError(f'Use the complete current application; modules not present: {sorted(missing_modules)}')
        report['modules'] = status['modules']

        # Section membership is read from the same shipped immutable input. Never
        # rebuild the index or prepare a reduced application to run this check.
        database = exe.parent / 'db/index.sqlite'
        with sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True) as connection:
            connection.execute('PRAGMA query_only=ON')
            sections = {did: json.loads(value) for did, value in
                        connection.execute('SELECT document_id,sections FROM documents')}
        if MOBILITY_SECTION not in sections.get(MOBILITY_ID, []) or POLICY_ID not in sections:
            raise AssertionError('The pinned complete document dataset is not available beside the EXE')

        raw = command('--search', QUESTION, '--limit', '10')
        validate_hits(raw, 10)
        if not any(hit['document_id'] == MOBILITY_ID for hit in raw):
            raise AssertionError('Existing natural search must retain the primary document for the full conversational query')
        report['raw_search'] = {'query': QUESTION, 'result_count': len(raw),
                                'document_ids': sorted({hit['document_id'] for hit in raw}), 'pass': True}

        case('conversational_mobility_conditions', QUESTION,
             required_document=MOBILITY_ID, informative=True)
        case('nominative_topic', 'академическая мобильность', required_document=MOBILITY_ID)
        case('genitive_topic', 'академической мобильности', required_document=MOBILITY_ID)
        case('leading_output_request', 'Дай рекомендации по академической мобильности',
             required_document=MOBILITY_ID)
        case('additional_output_connector',
             'Найди мне документ об академической мобильности, а также дай рекомендации',
             required_document=MOBILITY_ID)
        case('generic_tasks_without_topic',
             'Найди мне документ, напиши условия и дай рекомендации', expected_empty=True)
        case('another_document_topic', POLICY_QUESTION, required_document=POLICY_ID)
        case('practice_established_rules', PRACTICE_QUESTION,
             required_document=STUDY_RULES_ID, text_contains='ведут дневники практики')
        case('practice_how_it_works', 'Как проходит практика?',
             text_contains='практик')
        case('practice_professional_procedure',
             'Каков порядок прохождения производственной практики?',
             required_document=STUDY_RULES_ID, text_contains='производственной практик')
        case('practice_existing_obligations',
             'Какие обязанности при прохождении практики предусмотрены?',
             required_document=STUDY_RULES_ID, text_contains='ведут дневники практики')
        case('practice_rules_upstream_search', PRACTICE_QUESTION,
             search_command='--search')
        case('practice_rules_document_filter', PRACTICE_QUESTION,
             options=('--document-id', STUDY_RULES_ID),
             required_document=STUDY_RULES_ID, allowed_documents={STUDY_RULES_ID},
             text_contains='ведут дневники практики')
        case('practice_rules_section_filter', PRACTICE_QUESTION,
             options=('--section', MOBILITY_SECTION),
             required_document=PRACTICE_ID, allowed_section=MOBILITY_SECTION)
        case('practice_rules_unknown_subject',
             'Какие правила прохождения марсианской практики установлены?',
             expected_empty=True)
        case('practice_rules_unknown_date',
             'Какие правила прохождения практики установлены в 2099 году?',
             expected_empty=True)
        case('procedural_task_without_topic',
             'Какие правила и порядок прохождения установлены?', expected_empty=True)
        case('organization_behind_documents', 'Нормативные документы на базе какой организации?',
             text_contains='Сатпаев')
        case('organization_name', 'Как называется организация?', text_contains='Сатпаев')
        case('organization_represented_in_database',
             'Как называется организация, представленная в базе?', text_contains='Сатпаев')
        case('organization_represented_predicate',
             'Какая организация представлена в текущей базе?', text_contains='Сатпаев')
        case('organization_relative_clause',
             'Как называется организация, документы которой представлены в этой базе?',
             text_contains='Сатпаев')
        case('organization_represented_raw_search',
             'Как называется организация, представленная в базе?',
             search_command='--search', text_contains='Сатпаев')
        case('organization_represented_filtered',
             'Как называется организация, представленная в базе?',
             options=('--document-id', 'satbayev:document:27471'),
             allowed_documents={'satbayev:document:27471'}, text_contains='Сатпаев')
        case('organization_represented_unknown_subject',
             'Как называется организация, представленная в базе марсианского регламента 2099?',
             expected_empty=True)
        case('organization_rules_are_not_name_question',
             'Какие правила организации установлены?', search_command='--search', min_results=4)
        case('curriculum_leading_output', 'Расскажи об учебном плане')
        case('curriculum_question', 'Что говорится об учебном плане?')
        case('policy_location_question', 'Где найти академическую политику?',
             required_document=POLICY_ID)
        case('kazakh_education_question', 'Білім беру туралы қандай?')
        case('nonexistent_regulation_question', 'Что говорится про марсианский регламент 2099?',
             expected_empty=True)
        case('unknown_topic_with_organization_words',
             'Документы какой организации описывают марсианский регламент 2099?',
             expected_empty=True)
        case('upstream_search_curriculum_question', 'Расскажи об учебном плане',
             search_command='--search')
        case('upstream_search_policy_question', 'Где найти академическую политику?',
             search_command='--search', required_document=POLICY_ID)
        case('nonexistent_topic_with_generic_tasks',
             'Найди мне документ о несуществующаяксюя темаабвж, напиши условия и дай рекомендации',
             expected_empty=True)
        case('document_filter', QUESTION, options=('--document-id', MOBILITY_ID),
             required_document=MOBILITY_ID, allowed_documents={MOBILITY_ID})
        case('section_filter', QUESTION, options=('--section', MOBILITY_SECTION),
             required_document=MOBILITY_ID, allowed_section=MOBILITY_SECTION)
        case('combined_filters', QUESTION,
             options=('--document-id', MOBILITY_ID, '--section', MOBILITY_SECTION),
             required_document=MOBILITY_ID, allowed_documents={MOBILITY_ID},
             allowed_section=MOBILITY_SECTION)
        case('filters_intersect', QUESTION,
             options=('--document-id', POLICY_ID, '--section', MOBILITY_SECTION), expected_empty=True)
        case('single_result_limit', QUESTION, limit=1)
        case('positional_result_limit', QUESTION, limit=3, positional_limit=True)
        report['canonical_fragments_checked'] = len(canonical)
        report['pass'] = True
    except BaseException as error:
        report['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        report['canonical_fragments_checked'] = len(canonical)
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PASS native assistant retrieval: {len(report["cases"])} cases, '
          f'{len(canonical)} canonical fragments; report {report_path}')


if __name__ == '__main__':
    main()
