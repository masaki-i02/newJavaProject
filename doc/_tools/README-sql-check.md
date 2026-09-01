# 設計書の SQL を検証する

DB 設計書に書いた DDL が実際に PostgreSQL で通ることを確認する。
**設計書とマイグレーションが乖離するのを防ぐ最初の関門。**

```bash
# 設計書から DDL を抽出する
python3 doc/_tools/extract-sql.py doc/02_詳細設計/01_社員・組織/DB設計書.md > /tmp/ddl.sql

# 検証用のデータベースに適用する
docker compose up -d
docker compose exec -T db psql -U kintai -d kintai -v ON_ERROR_STOP=1 -f - < /tmp/ddl.sql
```

`extract-sql.py` は ```` ```sql ```` ブロックのうち、`SELECT` / `WITH` で始まるもの
（参照系のクエリ例）を除いて連結する。

## 制約が不正データを拒否することの確認

DDL が通るだけでは不十分で、**制約が意図どおり働くか** を確認する必要がある。
各 DB 設計書の「制約の検証」節に、確認済みのケースを ID つきで記載している。
これらは後に Testcontainers を用いた結合テストとして実装する。
