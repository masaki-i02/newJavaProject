#!/usr/bin/env python3
"""設計書の API 一覧と実装のエンドポイントを突き合わせる。

**なぜ要るか。**
テストは「存在する API」しか検査しない。設計書が定義していて実装が無い経路は、
テストが 1000 件あっても 1 件も落ちないまま何か月も残る（CLAUDE.md 落とし穴 138）。
実際に M4 の時点で 11 経路が欠けており、
就業規則の登録・改定は<strong>ユースケース 1 本ぶんが丸ごと</strong>無かった。

**警告では見過ごされるので、食い違ったら終了コード 1 で落とす**（落とし穴 89）。

判定の対象は「メソッドとパスの組」だけである。要求・応答の中身までは見ない。
そこまで見ようとすると設計書を機械可読にする必要があり、
**設計書が読みにくくなるほうの損が大きい。**
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.normpath(os.path.join(ROOT, '..', '02_詳細設計'))
CONTROLLERS = os.path.normpath(
    os.path.join(ROOT, '..', '..', 'src', 'backend', 'src', 'main', 'java'))

# 設計書の一覧表の行。「| `GET` | `/api/...` | 概要 | ロール |」
DESIGN_ROW = re.compile(r'^\|\s*`?(GET|POST|PUT|PATCH|DELETE)`?\s*\|\s*`([^`]+)`')
REQUEST_MAPPING = re.compile(r'@RequestMapping\("([^"]*)"\)')
# value = "..." の形も拾う。拾えないと「実装が無い」と誤って報告する
METHOD_MAPPING = re.compile(
    r'@(?:org\.springframework\.web\.bind\.annotation\.)?'
    r'(Get|Post|Put|Patch|Delete)Mapping'
    r'(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?')


def strip_comments(source):
    """コメントを落としてから探す。

    ★ 落とさないと、コメントアウトした {@code @GetMapping} を
      「実装がある」と数える。検査そのものが嘘をつく（落とし穴 87）。
      実際、この検査を書いたあとに 1 行コメントアウトして試したら、
      **落ちるはずのところで落ちなかった。**
    """
    without_block = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
    return re.sub(r'//[^\n]*', '', without_block)


def normalize(method, path):
    """経路変数の名前の違いを吸収する。{id} と {employeeId} は同じ経路である。"""
    return '%s %s' % (method, re.sub(r'\{[^}]*\}', '{}', path).rstrip('/') or '/')


def from_design():
    found = {}
    for base, _, names in os.walk(DESIGN):
        for name in sorted(names):
            if not name.endswith('.md'):
                continue
            path = os.path.join(base, name)
            with open(path, encoding='utf-8') as f:
                for line in f:
                    m = DESIGN_ROW.match(line)
                    if m and m.group(2).startswith('/api'):
                        key = normalize(m.group(1), m.group(2))
                        found.setdefault(key, os.path.relpath(path, DESIGN))
    return found


def from_code():
    found = {}
    for base, _, names in os.walk(CONTROLLERS):
        for name in sorted(names):
            if not name.endswith('Controller.java'):
                continue
            path = os.path.join(base, name)
            with open(path, encoding='utf-8') as f:
                source = strip_comments(f.read())
            m = REQUEST_MAPPING.search(source)
            prefix = m.group(1) if m else ''
            for match in METHOD_MAPPING.finditer(source):
                full = prefix + (match.group(2) or '')
                if full.startswith('/api'):
                    found.setdefault(normalize(match.group(1).upper(), full), name)
    return found


def main():
    design = from_design()
    code = from_code()
    problems = []
    for key in sorted(set(design) - set(code)):
        problems.append('設計書にあるが実装が無い: %s（%s）' % (key, design[key]))
    for key in sorted(set(code) - set(design)):
        problems.append('実装にあるが設計書に無い: %s（%s）' % (key, code[key]))

    print('設計 %d 経路 / 実装 %d 経路 / 一致 %d'
          % (len(design), len(code), len(set(design) & set(code))))
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
