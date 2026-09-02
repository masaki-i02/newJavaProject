-- 拡張と共通関数
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/（共通）/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

-- EXCLUDE 制約で uuid の等値比較と範囲型の重なり比較を
-- 1 つの GiST インデックスに同居させるために必要
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- DEFAULT now() は INSERT 時にしか効かない。UPDATE でも更新されるようトリガを置く
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
