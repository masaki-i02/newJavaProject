#!/usr/bin/env python3
"""本番コードの識別子が ASCII であることを確かめる。

日本語の識別子は Java の仕様としては合法（`Character.isJavaIdentifierStart`
が受け付ける）なので、**コンパイラは何も言わない。**
それでも本番コードでは使わないと決めている（CLAUDE.md 4.3）。

- 型名とテストメソッド名を日本語にすると、`.class` とレポート HTML の
  ファイル名になり、locale が POSIX / C の環境で書き出しが
  「Malformed input」で落ちる（落とし穴 29・74・129）
- 業務の言葉は javadoc に置く。識別子に置くと、grep も IDE の補完も
  効かないうえ、読める人と読めない人でコードの読みやすさが割れる

**テストコードは対象外。** 結合テスト仕様書 5 が定めるとおり、
シナリオは業務の記述であり、ヘルパのメソッド名は日本語でよい。

なぜ ArchUnit ではなく源泉を読むのか:
ArchUnit が見るのはバイトコードなので、**引数名と局所変数名が見えない**。
実際にすり抜けていたのは `MonthAlreadyClosedException(YearMonth, String 操作)`
という引数名だった。見えない場所を検査しないルールは、
「守られている」という見かけだけを増やす（落とし穴 87）。

使い方: cd doc/_tools && python3 check-identifiers.py
"""
import io
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
MAIN = os.path.join(ROOT, 'src', 'backend', 'src', 'main', 'java')

# 日本語（ひらがな・カタカナ・漢字）を含む Java の識別子。
# 全角英数字や記号は Java の識別子に使えないので見なくてよい
IDENTIFIER = re.compile(
    r'[A-Za-z_$0-9]*[ぁ-んァ-ヶー一-龥][A-Za-z_$0-9ぁ-んァ-ヶー一-龥]*')


def strip_comments_and_literals(source):
    """コメントと文字列リテラルを落とし、(行番号, コード) の列を返す。

    **注釈や javadoc に日本語があるのは正しい**（業務の言葉はそこに置くと
    決めている）ので、落とさずに数えると全ファイルが違反になる。
    """
    lines = {}
    index = 0
    line = 1
    state = None
    length = len(source)
    while index < length:
        char = source[index]
        if char == '\n':
            line += 1
        if state is None:
            if source.startswith('//', index):
                state = '//'
            elif source.startswith('/*', index):
                state = '/*'
            elif source.startswith('"""', index):
                state = '"""'
                index += 3
                continue
            elif char == '"':
                state = '"'
            elif char == "'":
                state = "'"
            else:
                lines.setdefault(line, []).append(char)
        elif state == '//':
            if char == '\n':
                state = None
        elif state == '/*':
            if source.startswith('*/', index):
                state = None
                index += 2
                continue
        elif state == '"""':
            if source.startswith('"""', index):
                state = None
                index += 3
                continue
        elif state == '"':
            if char == '\\':
                index += 2
                continue
            if char == '"':
                state = None
        elif state == "'":
            if char == '\\':
                index += 2
                continue
            if char == "'":
                state = None
        index += 1
    return sorted((number, ''.join(chars)) for number, chars in lines.items())


def main():
    if not os.path.isdir(MAIN):
        print(f'本番コードが見つからない: {MAIN}', file=sys.stderr)
        sys.exit(1)
    violations = []
    scanned = 0
    for base, _, names in sorted(os.walk(MAIN)):
        for name in sorted(names):
            if not name.endswith('.java'):
                continue
            path = os.path.join(base, name)
            scanned += 1
            source = io.open(path, encoding='utf-8').read()
            for number, code in strip_comments_and_literals(source):
                for match in IDENTIFIER.finditer(code):
                    violations.append(
                        (os.path.relpath(path, ROOT), number, match.group()))
    print(f'{scanned} ファイルを見た')
    for path, number, identifier in violations:
        print(f'日本語の識別子: {path}:{number} `{identifier}`', file=sys.stderr)
    if violations:
        print(f'{len(violations)} 件。本番コードの識別子は ASCII にする'
              '（CLAUDE.md 4.3）。業務の言葉は javadoc へ', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
