#!/usr/bin/env python3
"""DB設計書に書かれた ```sql ブロックを順に連結して 1 本の DDL として出力する。

設計書に書いた SQL が実際に PostgreSQL で通ることを検証するために使う。
設計書とマイグレーションが乖離するのを防ぐ最初の関門。
"""
import re
import sys
from pathlib import Path

blocks = []
for path in sys.argv[1:]:
    text = Path(path).read_text(encoding="utf-8")
    for block in re.findall(r"```sql\n(.*?)\n```", text, flags=re.S):
        # 参照用の SELECT / WITH はスキーマ定義ではないので除外する
        head = block.lstrip().upper()
        if head.startswith(("SELECT", "WITH")):
            continue
        blocks.append(f"-- from {Path(path).name}\n{block}")
print("\n\n".join(blocks))
