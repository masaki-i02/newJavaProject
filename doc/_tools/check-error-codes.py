#!/usr/bin/env python3
"""実装が返す Problem Details の型（errorCode）と、設計書が約束している型を突き合わせる。

**なぜ要るか。**
画面はエラーの `type` で分岐する（文言で分岐させると、メッセージを直した瞬間に
画面が壊れる）。つまり `type` は **API の契約**であり、設計書がその一覧を持っている。
ところが検査が無かったので、両側が静かに割れていた。

実際に見つかった食い違い:

* 設計書は `urn:kintai:error:department-cycle` を 409 と書いていたが、実装は
  `urn:kintai:error:cyclic-department-hierarchy` を 422 で返していた。
  **同じ設計書の別の節（IT-EMP-68）は正しい名前を書いており、文書の中で割れていた。**
  表だけを見て画面を作ると、決して一致しない型で分岐する（落とし穴 108・174）。
* 実装にあって設計書のどこにも無い型が 7 種あった。契約の側に穴が空いている。

`check-endpoints.py` が経路について行っていることを、エラーの型について行う。
**警告では見過ごされるので、食い違ったら終了コード 1 で落とす**（落とし穴 89）。

判定するのは「型の名前が両側に在るか」だけである。HTTP のステータスまでは見ない
（`DomainErrorKind` → HTTP の対応は presentation の網羅性検査つき switch が守っており、
ここで二重に持つと、まさにこの検査が防ごうとしている二重管理になる）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
DOC = os.path.normpath(os.path.join(ROOT, '..'))
JAVA = os.path.normpath(
    os.path.join(ROOT, '..', '..', 'src', 'backend', 'src', 'main', 'java'))

PREFIX = 'urn:kintai:error:'
# errorCode() の中の return と、ハンドラが直接立てる URI.create の両方を拾う
IMPL = re.compile(r'"' + re.escape(PREFIX) + r'([a-z0-9-]+)"')
# 設計書に現れる型。urn 付きでも短い名前でも書かれるので両方を拾う
DOC_URN = re.compile(re.escape(PREFIX) + r'([a-z0-9-]+)')
DOC_SHORT = re.compile(r'`([a-z][a-z0-9-]{3,})`')


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def walk(root, suffix):
    for dirpath, _, names in os.walk(root):
        for name in names:
            if name.endswith(suffix):
                yield os.path.join(dirpath, name)


def main():
    implemented = {}
    for path in walk(JAVA, '.java'):
        for code in IMPL.findall(read(path)):
            implemented.setdefault(code, os.path.relpath(path, JAVA))

    documented = set()
    doc_text = ''
    for path in walk(DOC, '.md'):
        text = read(path)
        doc_text += text
        documented.update(DOC_URN.findall(text))
        documented.update(DOC_SHORT.findall(text))

    missing_in_doc = sorted(c for c in implemented if c not in documented)
    # 設計書が urn 付きで書いた型は「実装がそれを返す」という約束である。
    # 短い名前は散文にも現れるので、こちらは urn 付きのものだけを見る
    promised = set(DOC_URN.findall(doc_text))
    missing_in_impl = sorted(c for c in promised if c not in implemented)

    print('実装が返す型: %d 種 / 設計書が urn で約束した型: %d 種'
          % (len(implemented), len(promised)))

    failed = False
    if missing_in_impl:
        failed = True
        print('\n設計書が約束しているのに実装が返さない型:', file=sys.stderr)
        for code in missing_in_impl:
            print('  %s%s' % (PREFIX, code), file=sys.stderr)
        print('  → 画面がこの型で分岐すると、決して一致しない', file=sys.stderr)
    if missing_in_doc:
        failed = True
        print('\n実装が返すのに設計書のどこにも無い型:', file=sys.stderr)
        for code in missing_in_doc:
            print('  %s%s  (%s)' % (PREFIX, code, implemented[code]), file=sys.stderr)
        print('  → 契約の側に穴がある。API 設計書のエラー一覧へ足すこと',
              file=sys.stderr)
    if failed:
        return 1
    print('食い違いなし')
    return 0


if __name__ == '__main__':
    sys.exit(main())
