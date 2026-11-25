#!/usr/bin/env python3
"""Troca os ícones Font Awesome (<i className="fa-...">) por <Icon name="..." /> (src/icons.tsx).
Rode dentro de web/:  python tools/svgify_icons.py
Mexe em src/App.tsx, src/Admin.tsx, src/styles.css e index.html. Pode rodar mais de uma vez."""
import re, pathlib, sys

root = pathlib.Path(__file__).resolve().parent.parent
left = 0
for name in ('App.tsx', 'Admin.tsx'):
    p = root / 'src' / name
    if not p.exists():
        continue
    s = p.read_text(encoding='utf-8')
    s = re.sub(r'<i className="fa-(?:solid|brands) fa-([a-z-]+?)( fa-spin)?" aria-hidden="true" />',
               lambda m: '<Icon name="%s"%s />' % (m.group(1), ' spin' if m.group(2) else ''), s)
    # className dinâmico: {reg ? 'fa-solid fa-a' : 'fa-solid fa-b'}
    s = re.sub(r"<i className=\{(.+?) \? 'fa-(?:solid|brands) fa-([a-z-]+)' : 'fa-(?:solid|brands) fa-([a-z-]+)'\} aria-hidden=\"true\" />",
               lambda m: "<Icon name={%s ? '%s' : '%s'} />" % (m.group(1), m.group(2), m.group(3)), s)
    if '<Icon ' in s and "from './icons'" not in s:
        imports = list(re.finditer(r"^import .*;\n", s, flags=re.M))
        i = imports[-1].end()
        s = s[:i] + "import { Icon } from './icons';\n" + s[i:]
    rest = re.findall(r'<i [^>]*fa-[^>]*>', s)
    left += len(rest)
    for r in rest:
        print('AVISO: ícone não convertido em %s: %s' % (name, r))
    p.write_text(s, encoding='utf-8')

css = root / 'src' / 'styles.css'
c = css.read_text(encoding='utf-8')
for old, new in [
    ('.btn i, nav.menu a i, .cart-btn i {', '.btn .ico, nav.menu a .ico, .cart-btn .ico {'),
    ('.icon-btn i, .modal-head h2 i {', '.icon-btn .ico, .modal-head h2 .ico {'),
    ('.cart-empty > i { font-size: 2.2rem; color: var(--line); }', '.cart-empty > .ico { width: 2.2rem; height: 2.2rem; color: var(--line); }'),
    ('.cover-badge i {', '.cover-badge .ico {'),
]:
    if old in c:
        c = c.replace(old, new)
if '.ico {' not in c:
    c += '''
/* ---------- ícones SVG ---------- */
.ico { width: 1em; height: 1em; flex: none; vertical-align: -0.14em; }
.ico.spin { animation: ico-spin .8s linear infinite; }
@keyframes ico-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .ico.spin { animation-duration: 2.4s; } }
'''
css.write_text(c, encoding='utf-8')

html = root / 'index.html'
h = html.read_text(encoding='utf-8')
if left == 0 and not any('fa-' in (root / 'src' / n).read_text(encoding='utf-8') for n in ('App.tsx', 'Admin.tsx') if (root / 'src' / n).exists()):
    h2 = re.sub(r'\s*<link rel="stylesheet" href="https://cdnjs\.cloudflare\.com/ajax/libs/font-awesome[^>]*>\n?', '\n', h)
    if h2 != h:
        html.write_text(h2, encoding='utf-8')
        print('Font Awesome removido do index.html')
print('ok')
sys.exit(1 if left else 0)
