"""Development-only reproducible Unicode tables. Never shipped as a runtime dependency."""
import sys, unicodedata
from pathlib import Path

if unicodedata.unidata_version != '15.0.0':
    raise SystemExit('Requires Python Unicode 15.0.0, got ' + unicodedata.unidata_version)
destination = Path(__file__).resolve().parents[1] / 'resources/ai/enterprise/search/unicode15'
destination.mkdir(parents=True, exist_ok=True)
fold, canonical, combining, composition, alnum = [], [], [], [], []
start = None
for cp in range(0x110000):
    c = chr(cp)
    folded = c.casefold()
    if folded != c:
        fold.append(f'{cp:X};'+' '.join(f'{ord(x):X}' for x in folded))
    decomposed = unicodedata.decomposition(c)
    if decomposed and not decomposed.startswith('<'):
        canonical.append(f'{cp:X};{decomposed}')
        sequence = ''.join(chr(int(x,16)) for x in decomposed.split())
        if len(sequence)==2 and unicodedata.normalize('NFC',sequence)==c:
            composition.append(f'{decomposed};{cp:X}')
    cc = unicodedata.combining(c)
    if cc: combining.append(f'{cp:X};{cc}')
    if c.isalnum() and start is None: start = cp
    if start is not None and (not c.isalnum() or cp==0x10FFFF):
        alnum.append(f'{start:X};{cp-1 if not c.isalnum() else cp:X}')
        start = None
for name, data in [('casefold',fold),('canonical',canonical),('combining',combining),('composition',composition),('alnum',alnum)]:
    (destination/(name+'.txt')).write_text('# Unicode 15.0.0; generated from Python 3.12; Unicode terms apply.\n'+'\n'.join(data)+'\n',encoding='ascii')
print('Generated Unicode 15 tables:', ', '.join(f'{n}={len(v)}' for n,v in [('fold',fold),('decomposition',canonical),('ccc',combining),('composition',composition),('alnum ranges',alnum)]))
