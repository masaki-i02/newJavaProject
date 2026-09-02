#!/usr/bin/env python3
"""詳細設計書の DDL から Flyway のマイグレーションを生成する。

**設計書を正とし、マイグレーションは生成物とする。**
手で写すと転記ミスが入り、設計書と実際のスキーマが静かにずれる。
DDL は設計書側で 104 件の制約検証を通しているので、そこを唯一の正とする。

使い方: cd doc/_tools && python3 build-migrations.py
"""
import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
DESIGN = os.path.join(ROOT, 'doc', '02_詳細設計')
OUT = os.path.join(ROOT, 'src', 'backend', 'src', 'main', 'resources', 'db', 'migration')

# 適用順。外部キーの依存に従う
PLAN = [
    ('V1__extensions_and_functions.sql', None, '拡張と共通関数'),
    ('V2__employee.sql', '01_社員・組織', '社員・組織'),
    ('V3__workrule.sql', '02_就業規則・カレンダー', '就業規則・カレンダー'),
    ('V4__attendance.sql', '03_勤怠_打刻と日次集計', '勤怠（打刻・日次集計）'),
    ('V5__settlement.sql', '04_勤怠_月次清算', '勤怠（月次清算）'),
    ('V6__approval.sql', '05_申請承認と締め', '申請・承認・締め'),
]

# V1 へ切り出すもの。どのコンテキストからも使うため先頭に置く
V1_BODY = """-- EXCLUDE 制約で uuid の等値比較と範囲型の重なり比較を
-- 1 つの GiST インデックスに同居させるために必要
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- DEFAULT now() は INSERT 時にしか効かない。UPDATE でも更新されるようトリガを置く
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
"""

# V1 へ移したので、各コンテキストの抽出からは取り除く
STRIP = [
    re.compile(r'CREATE EXTENSION IF NOT EXISTS btree_gist;\s*', re.I),
    re.compile(r'CREATE OR REPLACE FUNCTION set_updated_at\(\).*?\$\$ LANGUAGE plpgsql;\s*',
               re.I | re.S),
]

FENCE = re.compile(r'^```sql\s*$')
END = re.compile(r'^```\s*$')
# 参照用のクエリは DDL ではない
QUERY = re.compile(r'^\s*(SELECT|WITH|INSERT|UPDATE|DELETE)\b', re.I | re.M)


def extract(directory):
    """そのコンテキストの DB設計書から DDL のブロックだけを取り出す。"""
    path = os.path.join(DESIGN, directory, 'DB設計書.md')
    blocks = []
    inside = False
    buf = []
    for line in io.open(path, encoding='utf-8'):
        if not inside:
            if FENCE.match(line):
                inside, buf = True, []
            continue
        if END.match(line):
            inside = False
            body = ''.join(buf)
            if not QUERY.match(body.lstrip()):
                blocks.append(body.rstrip())
            continue
        buf.append(line)
    return blocks


def main():
    os.makedirs(OUT, exist_ok=True)
    written = []
    for filename, directory, label in PLAN:
        header = (
            f'-- {label}\n'
            f'--\n'
            f'-- このファイルは生成物である。直接編集しない。\n'
            f'-- 正は doc/02_詳細設計/{directory or "（共通）"}/DB設計書.md であり、\n'
            f'-- `cd doc/_tools && python3 build-migrations.py` で生成する。\n'
            f'\n'
        )
        if directory is None:
            body = V1_BODY
        else:
            body = '\n\n'.join(extract(directory)) + '\n'
            for pattern in STRIP:
                body = pattern.sub('', body)
            body = re.sub(r'\n{3,}', '\n\n', body).strip() + '\n'
        io.open(os.path.join(OUT, filename), 'w', encoding='utf-8').write(header + body)
        written.append((filename, body.count('CREATE TABLE')))

    for name, tables in written:
        print(f'{name}: CREATE TABLE {tables} 件')

    # 生成しただけでは意味がない。実際に適用して確かめる
    if '--verify' in sys.argv:
        verify()


def verify():
    """生成物を実際の PostgreSQL へ順に適用する。"""
    env = dict(os.environ, PGPASSWORD='postgres')
    args = ['psql', '-h', '127.0.0.1', '-p', '55432', '-U', 'postgres',
            '-v', 'ON_ERROR_STOP=1', '-q']
    subprocess.run(args + ['-d', 'postgres', '-c', 'DROP DATABASE IF EXISTS migcheck',
                           '-c', 'CREATE DATABASE migcheck'], env=env, check=True)
    for filename, _, _ in PLAN:
        r = subprocess.run(args + ['-d', 'migcheck', '-f', os.path.join(OUT, filename)],
                           env=env, capture_output=True, text=True)
        if r.returncode != 0:
            print(f'NG   {filename}\n{r.stderr}', file=sys.stderr)
            sys.exit(1)
        print(f'OK   {filename}')
    print('すべてのマイグレーションが適用できた')


if __name__ == '__main__':
    main()
