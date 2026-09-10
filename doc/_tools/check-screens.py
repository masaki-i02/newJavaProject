#!/usr/bin/env python3
"""画面設計書の画面一覧（`SC-xx`）と、実装の画面を突き合わせる。

**なぜ要るか。**
経路（`check-endpoints.py`）・エラーの型（`check-error-codes.py`）・
テストの観点（`collect-tests.py`）・識別子（`check-identifiers.py`）には
機械的な突き合わせがあるのに、**画面だけは手で数えていた。**

このプロジェクトは「テストが全部緑」を「実装が揃っている」と読んで
何度も痛い目を見ている（落とし穴 155・138・179）。
テストは<strong>存在する画面しか検査しない</strong>ので、
設計書が定義していて実装が無い画面は、実ブラウザの通しが 12 件あっても
1 件も落ちない。

**両方向に落とす**（落とし穴 189）。

- 設計書にある `SC-xx` を、どの画面ファイルも名乗っていない … 未実装
- 画面ファイルが名乗る `SC-xx` が設計書に無い … 採番したまま設計書へ戻していない

**名乗るのはファイル先頭の javadoc の 1 行目だけ**とする。
本文中の相互参照（`★ 訂正の画面（SC-04）でも同じ言葉を使う`）や
型定義のコメント（`月次勤怠（SC-03 / SC-06）`）まで拾うと、
**言及しただけの画面を「実装がある」と数えてしまう**（落とし穴 156 と同型）。

1 つのファイルが 2 つの画面を持つことは認める
（`SC-05 承認待ち一覧 / SC-06 部下の月次勤怠` は 1 画面の中の一覧と詳細である）。

使い方: cd doc/_tools && python3 check-screens.py
"""
import io
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
DESIGN = os.path.join(ROOT, '02_詳細設計', '90_画面', '画面設計書.md')
SCREENS = os.path.normpath(
    os.path.join(ROOT, '..', 'src', 'frontend', 'src', 'screens'))

# 画面一覧の行。`| SC-01 | ログイン | UC-08 | – | ... |`
ROW = re.compile(r'^\|\s*`?(SC-\d+)`?\s*\|\s*([^|]+?)\s*\|')
# 画面ファイルの先頭 javadoc の 1 行目。`* SC-05 承認待ち一覧 / SC-06 部下の月次勤怠。`
HEADER = re.compile(r'^\s*\*\s*(SC-\d+\b.*)$')
ID = re.compile(r'SC-\d+')


def defined():
    """設計書の画面一覧（ID → 画面名）。"""
    found = {}
    for line in io.open(DESIGN, encoding='utf-8'):
        m = ROW.match(line.rstrip('\n'))
        if m:
            found.setdefault(m.group(1), m.group(2))
    return found


def claimed():
    """画面ファイルが名乗っている ID → ファイル名。"""
    found = {}
    for name in sorted(os.listdir(SCREENS)):
        if not name.endswith('.tsx'):
            continue
        path = os.path.join(SCREENS, name)
        for line in io.open(path, encoding='utf-8'):
            m = HEADER.match(line.rstrip('\n'))
            if not m:
                continue
            # ★ 先頭の 1 行だけを見る。以降の行は相互参照でありうる
            for screen in ID.findall(m.group(1)):
                found.setdefault(screen, []).append(name)
            break
    return found


def main():
    if not os.path.isfile(DESIGN) or not os.path.isdir(SCREENS):
        print('画面設計書か画面の置き場所が見つからない', file=sys.stderr)
        sys.exit(1)
    design = defined()
    impl = claimed()
    print('設計書の画面: %d ／ 実装が名乗る画面: %d' % (len(design), len(impl)))

    problems = []
    for screen in sorted(design, key=lambda s: int(s.split('-')[1])):
        if screen not in impl:
            problems.append('%s（%s）を名乗る画面が無い' % (screen, design[screen]))
    for screen in sorted(impl, key=lambda s: int(s.split('-')[1])):
        if screen not in design:
            problems.append('%s は画面設計書の一覧に無い（%s）'
                            % (screen, ', '.join(impl[screen])))
        elif len(impl[screen]) > 1:
            problems.append('%s を %d 個のファイルが名乗っている: %s'
                            % (screen, len(impl[screen]), ', '.join(impl[screen])))
    for problem in problems:
        print('画面が食い違っている: %s' % problem, file=sys.stderr)
    if problems:
        sys.exit(1)


if __name__ == '__main__':
    main()
