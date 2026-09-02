#!/usr/bin/env bash
# Docker が使えない環境で、統合テスト用の PostgreSQL 16 を起動する。
#
# 本来は Testcontainers が担う。この手順は Docker が使えない環境のための代替であり、
# バージョンは設計書の DDL を検証したのと同じ 16 にそろえる（CLAUDE.md 落とし穴 21）。
set -euo pipefail

PGBIN=${PGBIN:-/usr/lib/postgresql/16/bin}
PGDATA=${PGDATA:-/tmp/kintai-pgdata}
PGPORT=${PGPORT:-55432}

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  mkdir -p "$PGDATA"
  chown postgres:postgres "$PGDATA"
  su postgres -c "$PGBIN/initdb -D $PGDATA -U postgres --encoding=UTF8 --locale=C" >/dev/null
fi
chown -R postgres:postgres "$PGDATA"

if su postgres -c "$PGBIN/pg_ctl -D $PGDATA status" >/dev/null 2>&1; then
  echo "既に起動している (port $PGPORT)"
else
  # 打刻の制約トリガが AT TIME ZONE を使うので、タイムゾーンも固定する
  su postgres -c "$PGBIN/pg_ctl -D $PGDATA \
      -o \"-p $PGPORT -k /tmp -c listen_addresses=127.0.0.1 -c timezone=Asia/Tokyo\" \
      -l /tmp/pg.log start" >/dev/null
  echo "起動した (port $PGPORT)"
fi

su postgres -c "psql -h 127.0.0.1 -p $PGPORT -d postgres -q \
    -c 'DROP DATABASE IF EXISTS kintai_test' -c 'CREATE DATABASE kintai_test'" 2>/dev/null
echo "kintai_test を作り直した"
