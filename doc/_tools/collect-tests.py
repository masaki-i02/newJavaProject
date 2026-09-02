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

ROW = re.compile(r'^\|\s*(?:\*\*)?(?P<id>(?:UT|IT)-[A-Z0-9]+-\d+)(?:\*\*)?\s*\|(?P<rest>.*)\|\s*$')


def cells(rest):
    """行の残りをセルへ分割する。末尾の空セルは落とす。"""
    parts = [c.strip() for c in rest.split('|')]
    while parts and parts[-1] == '':
        parts.pop()
    return parts


def collect(prefix):
    """(コンテキスト, 出典ファイル, ID, 観点, 期待, 参照) を返す。"""
    found = []
    seen = {}
    for directory, label in CONTEXTS:
        base = os.path.join(DESIGN, directory)
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            if not name.endswith('.md'):
                continue
            path = os.path.join(base, name)
            for line in io.open(path, encoding='utf-8'):
                m = ROW.match(line.rstrip('\n'))
                if not m or not m.group('id').startswith(prefix + '-'):
                    continue
                c = cells(m.group('rest'))
                tid = m.group('id')
                if tid in seen:
                    print(f'警告: {tid} が重複しています（{seen[tid]} と {label}/{name}）',
                          file=sys.stderr)
                seen[tid] = f'{label}/{name}'
                found.append({
                    'context': label,
                    'source': f'{directory}/{name}',
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
    for _, label in CONTEXTS:
        part = [r for r in rows if r['context'] == label]
        if not part:
            continue
        out.append(f'## {label}（{len(part)} 件）')
        out.append('')
        out.append('| ID | 観点 | 期待 | 参照 | 出典 |')
        out.append('| --- | --- | --- | --- | --- |')
        for r in part:
            src = r['source']
            link = f'[{os.path.basename(src)}](../02_詳細設計/{src})'
            out.append(f"| `{r['id']}` | {r['view']} | {r['expect']} | {r['ref']} | {link} |")
        out.append('')
    return '\n'.join(out) + '\n'


def main():
    for prefix, path, title in TARGETS:
        rows = collect(prefix)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        io.open(path, 'w', encoding='utf-8').write(render(prefix, title, rows))
        print(f'{prefix}: {len(rows)} 件 → {os.path.relpath(path, ROOT)}')


if __name__ == '__main__':
    main()
