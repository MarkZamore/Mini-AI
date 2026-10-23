"""Standalone report figures from small development result JSON, never final."""
import hashlib
import importlib.metadata
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESEARCH = ROOT / 'src/build/research-20261024'
TOOLS = RESEARCH / 'report-tools'
sys.path.insert(0, str(TOOLS))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

OUT = pathlib.Path('C:/Users/Oskar/Desktop/Исследование_локального_ИИ_2026-10-24/appendices/figures')


def read(path):
    return json.loads((RESEARCH / path).read_text(encoding='utf-8'))


def save(fig, name):
    fig.savefig(OUT / (name + '.png'), dpi=180, facecolor='white')
    fig.savefig(OUT / (name + '.svg'), facecolor='white')
    plt.close(fig)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11,
                         'axes.spines.top': False, 'axes.spines.right': False,
                         'axes.titleweight': 'bold', 'svg.fonttype': 'none'})
    native = read('native-pipeline-comparison/comparison.json')
    names = ['lexical', 'untrained_hybrid', 'hybrid', 'hybrid_bge8']
    labels = ['Лексический', 'Hybrid\nисходный E5', 'Hybrid\nобученный E5', 'Hybrid + BGE8\nвыигрыш не доказан']
    colors = ['#9aa7b1', '#4b86b4', '#16766d', '#ad9ab8']
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), constrained_layout=True)
    for ax, metric, title in zip(axes, ['recall_at_10', 'ndcg_at_10'], ['Найден нужный фрагмент в top10', 'Порядок нужного фрагмента: nDCG@10']):
        vals = [native['runs'][n]['metrics'][metric] for n in names]
        bars = ax.bar(labels, vals, color=colors, width=.62)
        ax.bar_label(bars, fmt='%.3f', padding=4, fontsize=10)
        ax.set_ylim(0, 1.10)
        ax.set_yticks(np.arange(0, 1.01, .2))
        ax.set_title(title, fontsize=12)
        ax.grid(axis='y', alpha=.18)
        ax.set_axisbelow(True)
        ax.tick_params(axis='x', labelsize=9)
    fig.suptitle('Настоящий поиск полного приложения: selection80, один и тот же корпус', fontsize=14)
    fig.supxlabel('5 семейств; 72/80 вопросов из одного. Это retrieval proxy, не точность нормативных ответов.', fontsize=10)
    save(fig, 'retrieval-stages')

    review = read('generator/independent-review.json')
    rows = {(r['set'], r['model']): r for r in review['metrics']}
    models = ['4B-Q6_K', '9B-Q4_K_M', '4B-trained-A-Q6_K']
    labels = ['4B исходная', '9B исходная', '4B обученная A']
    fig, axes = plt.subplots(1, 2, figsize=(11.5, 4.8), constrained_layout=True)
    x = np.arange(3)
    for ax, key, den, title in zip(axes,
        ['literal_atoms_present', 'correct_positive_citation_cases'],
        ['literal_atoms_total', 'cases'],
        ['Буквальные атомы источника (micro)', 'Правильная полная положительная ссылка']):
        for offset, subset, color, legend in [(-.18, 'oracle', '#4b86b4', 'Положительный контекст'), (.18, 'stress', '#16766d', 'Отвлечения, alias2/3')]:
            rr = [rows[(subset, m)] for m in models]
            vals = [r[key] / r[den] for r in rr]
            bars = ax.bar(x + offset, vals, .34, color=color, label=legend)
            ax.bar_label(bars, labels=[f'{r[key]}/{r[den]}' for r in rr], padding=4, fontsize=10)
        ax.set_xticks(x, labels)
        ax.set_ylim(0, 1.16)
        ax.set_yticks(np.arange(0, 1.01, .2))
        ax.set_title(title, fontsize=11)
        ax.grid(axis='y', alpha=.18)
        ax.set_axisbelow(True)
    axes[0].legend(fontsize=9, loc='upper left')
    fig.suptitle('Узкое native-сравнение генераторов: 16 вопросов, 8 RU / 8 KZ', fontsize=14)
    fig.supxlabel('15/16 из одного семейства. Цитата и буквальный атом не доказывают семантическую полноту.', fontsize=10)
    save(fig, 'generator-proxies')

    a1 = read('gpu-worker/a1-summary.json')
    metrics = ['startup_seconds', 'ttft_seconds', 'generation_seconds', 'total_seconds']
    labels = ['Запуск → ready', 'generate →\nпервый текст', 'generate → done', 'Запуск → done']
    fig, ax = plt.subplots(figsize=(10.5, 4.8), constrained_layout=True)
    x = np.arange(4)
    for shift, backend, color in [(-.18, 'cpu', '#4b86b4'), (.18, 'cuda', '#16766d')]:
        bars = ax.bar(x + shift, [a1[backend][m] for m in metrics], .34, label=backend.upper(), color=color)
        ax.bar_label(bars, fmt='%.3f', padding=4, fontsize=10)
    ax.set_xticks(x, labels)
    ax.set_ylabel('Медиана, секунды')
    ax.set_ylim(0, 22)
    ax.legend()
    ax.grid(axis='y', alpha=.18)
    ax.set_axisbelow(True)
    ax.set_title('CPU/CUDA worker: те же веса и пять входов, свежий процесс на вопрос', fontsize=13)
    fig.supxlabel('Без поиска и UI; Teams закрыт; кеш не очищен; 4/5 выходов различаются. Не чистый throughput factor.', fontsize=10)
    save(fig, 'worker-latency')

    inputs = ['native-pipeline-comparison/comparison.json', 'generator/independent-review.json', 'gpu-worker/a1-summary.json']
    manifest = {'final_read': False, 'source_files': {p: hashlib.sha256((RESEARCH / p).read_bytes()).hexdigest() for p in inputs},
                'dependencies': {d.metadata['Name']: d.version for d in importlib.metadata.distributions(path=[str(TOOLS)])},
                'figures': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.iterdir() if p.suffix in ('.png', '.svg')},
                'semantics': 'Development diagnostics only; no final measurements or semantic answer accuracy represented'}
    (OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'figures': str(OUT), 'count': len(manifest['figures']), 'final_read': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
