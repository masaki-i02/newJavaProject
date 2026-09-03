#!/usr/bin/env python3
"""詳細設計書に散在するテスト ID を集約し、一覧を生成する。

図を .mmd から .png へ変換するのと同じ考え方で、
**詳細設計書を正とし、一覧は生成物とする。**
一覧を手で書くと、設計書を直したときに必ず片方だけが古くなる。

使い方: cd doc/_tools && python3 collect-tests.py
"""
import os
import re
import io
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
DESIGN = os.path.join(ROOT, '02_詳細設計')

CONTEXTS = [
    # 業務コンテキストに属さない横断的な観点（スキーマ・アーキテクチャ）もここから拾う
    ('00_共通', '共通'),
    ('01_社員・組織', '社員・組織'),
    ('02_就業規則・カレンダー', '就業規則・カレンダー'),
    ('03_勤怠_打刻と日次集計', '勤怠（打刻・日次集計）'),
    ('04_勤怠_月次清算', '勤怠（月次清算）'),
    ('05_申請承認と締め', '申請・承認・締め'),
]

TARGETS = [
    ('UT', os.path.join(ROOT, '03_単体テスト', 'テストケース一覧.md'), '単体テストケース一覧'),
    ('IT', os.path.join(ROOT, '04_結合テスト', 'テストケース一覧.md'), '結合テストケース一覧'),
]

# ID は `IT-SCN-01` のようにバッククォートで囲まれていることもある。
# 囲みを許さないと、その行を黙って落として一覧に現れなくなる（CLAUDE.md 落とし穴 45）
ROW = re.compile(
    r'^\|\s*[`*]*(?P<id>(?:UT|IT)-[A-Z0-9]+-\d+)[`*]*\s*\|(?P<rest>.*)\|\s*$')


def cells(rest):
    """行の残りをセルへ分割する。末尾の空セルは落とす。"""
    parts = [c.strip() for c in rest.split('|')]
    while parts and parts[-1] == '':
        parts.pop()
    return parts


# 業務コンテキストに属さない横断的な観点は、テスト仕様書そのものが正になる。
# シナリオ（IT-SCN）は複数のコンテキストをまたぐので、どの設計書にも置き場が無い
EXTRA_SOURCES = [
    ('シナリオ・横断', os.path.join(ROOT, '04_結合テスト', '結合テスト仕様書.md')),
]


def collect(prefix):
    """(コンテキスト, 出典ファイル, ID, 観点, 期待, 参照) を返す。"""
    found = []
    seen = {}
    sources = []
    for directory, label in CONTEXTS:
        base = os.path.join(DESIGN, directory)
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            if name.endswith('.md'):
                sources.append((label, '%s/%s' % (directory, name),
                                os.path.join(base, name)))
    extras = []
    for label, path in EXTRA_SOURCES:
        if os.path.isfile(path):
            extras.append((label, os.path.relpath(path, ROOT), path))

    # 設計書を先に読み、そこで定義されなかった ID だけを仕様書から拾う
    for label, source, path in sources + extras:
        is_extra = (label, source, path) in extras
        for line in io.open(path, encoding='utf-8'):
            m = ROW.match(line.rstrip('\n'))
            if not m or not m.group('id').startswith(prefix + '-'):
                continue
            c = cells(m.group('rest'))
            tid = m.group('id')
            if tid in seen:
                # 仕様書は、設計書で定義済みの ID を参照として並べることがある。
                # 定義が先にあるならそちらを採り、重複としては数えない
                if is_extra:
                    continue
                print(f'警告: {tid} が重複しています（{seen[tid]} と {source}）',
                      file=sys.stderr)
            seen[tid] = source
            found.append({
                'context': label,
                'source': source,
                'id': tid,
                'view': c[0] if len(c) > 0 else '',
                'expect': c[1] if len(c) > 1 else '',
                'ref': c[2] if len(c) > 2 else '',
            })
    return found


def render(prefix, title, rows):
    out = []
    out.append(f'# {title}')
    out.append('')
    out.append('| 項目 | 内容 |')
    out.append('| --- | --- |')
    out.append(f'| 件数 | **{len(rows)} 件** |')
    out.append('| 生成元 | `doc/02_詳細設計/**/*.md` の「テストの観点」「制約の検証」節 |')
    out.append('| 生成方法 | `cd doc/_tools && python3 collect-tests.py` |')
    out.append('')
    out.append('> **この文書は生成物である。直接編集しない。**')
    out.append('> 内容を変えるときは詳細設計書の側を直し、生成し直す。')
    out.append('> 一覧を手で書くと、設計書を直したときに片方だけが古くなる。')
    out.append('')
    out.append('---')
    out.append('')
    out.append(render_by_requirement(rows))
    labels = [label for _, label in CONTEXTS]
    labels += [label for label, _ in EXTRA_SOURCES if label not in labels]
    for label in labels:
        part = [r for r in rows if r['context'] == label]
        if not part:
            continue
        out.append(f'## {label}（{len(part)} 件）')
        out.append('')
        out.append('| ID | 観点 | 期待 | 参照 | 出典 |')
        out.append('| --- | --- | --- | --- | --- |')
        for r in part:
            src = r['source']
            link = (f'[{os.path.basename(src)}](../02_詳細設計/{src})'
                    if not src.startswith('04_') and not src.startswith('03_')
                    else f'[{os.path.basename(src)}](../{src})')
            out.append(f"| `{r['id']}` | {r['view']} | {r['expect']} | {r['ref']} | {link} |")
        out.append('')
    return '\n'.join(out) + '\n'


def render_by_requirement(rows):
    """要件 ID（BR-xx）ごとの件数。

    **手で数えない。** 仕様書に件数を書いていたが、
    ケースを足すたびに合わなくなり、5 行が実際と食い違っていた。
    """
    counts = {}
    for r in rows:
        for br in sorted(set(re.findall(r'BR-\d+', r['ref']))):
            counts.setdefault(br, []).append(r['id'])
    if not counts:
        return ''
    out = ['## 要件ごとの件数', '',
           '| 要件 | 件数 |', '| --- | --- |']
    for br in sorted(counts, key=lambda b: int(b.split('-')[1])):
        out.append(f'| `{br}` | {len(counts[br])} |')
    untraced = [r['id'] for r in rows if not re.search(r'BR-\d+', r['ref'])]
    out.append(f'| （要件に紐づかない） | {len(untraced)} |')
    out.append('')
    return '\n'.join(out)


def main():
    for prefix, path, title in TARGETS:
        rows = collect(prefix)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        io.open(path, 'w', encoding='utf-8').write(render(prefix, title, rows))
        print(f'{prefix}: {len(rows)} 件 → {os.path.relpath(path, ROOT)}')


if __name__ == '__main__':
    main()
