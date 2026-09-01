# 勤怠（打刻・日次集計） DB設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-302 |
| 版 | 0.1 |
| 対象スキーマ | `time_clock_events` / `daily_attendances` / `daily_attendance_slices` |
| 関連要件 | BR-01 / BR-02 / BR-03 / BR-09 |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) |

---

## 1. ER 図

![ER図](images/ER図.png)

<sub>図のソース: [`diagrams/ER図.mmd`](diagrams/ER図.mmd)</sub>

---

## 2. 設計の要点

| # | 設計 | 理由 |
| --- | --- | --- |
| 1 | 打刻を **追記専用** にする。更新も削除もしない | 打刻は労働時間の一次証拠。訂正で上書きすると「元は何時だったか」が失われる（BR-09） |
| 2 | 訂正を **取消行の追記** で表現する | 履歴の欠落を構造的に防ぐ |
| 3 | `time_clock_events` を `work_date` でレンジパーティションにする | 100 名 × 240 日 × 4 回で年間 10 万行、5 年で 50 万行。単調増加するため最初から分割する |
| 4 | 日次勤怠を **計算結果のスナップショット** として保存する | 締めた後に就業規則を改定しても、確定済みの値が動いてはいけない |
| 5 | 内訳（`daily_attendance_slices`）を正規化して保持する | 「なぜこの残業時間か」を提示できないと労務の問い合わせに答えられない |
| 6 | 集計値の整合を CHECK 制約で保証する | ドメインの不変条件と対にする。集計ロジックを壊す変更を DB が拒否する |

---

## 3. テーブル定義

### 3.1 time_clock_events（打刻イベント）

```sql
CREATE TABLE time_clock_events (
    id                uuid        NOT NULL,
    work_date         date        NOT NULL,
    employee_id       uuid        NOT NULL REFERENCES employees (id),
    entry_type        varchar(20) NOT NULL,
    event_type        varchar(20) NOT NULL,
    occurred_at       timestamptz NOT NULL,
    source            varchar(20) NOT NULL DEFAULT 'WEB',
    revokes_event_id  uuid,
    reason            varchar(200),
    recorded_by       uuid        NOT NULL REFERENCES employees (id),
    created_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT time_clock_events_entry_type_check
        CHECK (entry_type IN ('ENTRY', 'REVOCATION')),
    CONSTRAINT time_clock_events_event_type_check
        CHECK (event_type IN ('CLOCK_IN', 'CLOCK_OUT', 'BREAK_START', 'BREAK_END')),
    CONSTRAINT time_clock_events_source_check
        CHECK (source IN ('WEB', 'MOBILE', 'IC_CARD', 'IMPORT', 'ADMIN')),

    -- 取消行は必ず対象を持ち、通常の打刻は持たない
    CONSTRAINT time_clock_events_revocation_check CHECK (
        (entry_type = 'REVOCATION' AND revokes_event_id IS NOT NULL AND reason IS NOT NULL)
        OR
        (entry_type = 'ENTRY' AND revokes_event_id IS NULL)
    ),

    -- パーティションキーを主キーに含めるのは PostgreSQL の制約
    PRIMARY KEY (work_date, id),

    -- 取消対象は同じ勤務日の打刻でなければならない。
    -- 複合キーで参照することで、同一パーティション内であることも同時に保証される
    CONSTRAINT time_clock_events_revokes_fk
        FOREIGN KEY (work_date, revokes_event_id)
        REFERENCES time_clock_events (work_date, id)
) PARTITION BY RANGE (work_date);

-- 同じ打刻を二重に取り消せない
CREATE UNIQUE INDEX time_clock_events_revokes_uk
    ON time_clock_events (work_date, revokes_event_id)
    WHERE revokes_event_id IS NOT NULL;

CREATE INDEX time_clock_events_employee_date_idx
    ON time_clock_events (employee_id, work_date, occurred_at);

-- 想定運用期間ぶんのパーティション。実運用では pg_partman 等で自動生成する
CREATE TABLE time_clock_events_2026q1 PARTITION OF time_clock_events
    FOR VALUES FROM ('2026-01-01') TO ('2026-04-01');
CREATE TABLE time_clock_events_2026q2 PARTITION OF time_clock_events
    FOR VALUES FROM ('2026-04-01') TO ('2026-07-01');
CREATE TABLE time_clock_events_2026q3 PARTITION OF time_clock_events
    FOR VALUES FROM ('2026-07-01') TO ('2026-10-01');
CREATE TABLE time_clock_events_2026q4 PARTITION OF time_clock_events
    FOR VALUES FROM ('2026-10-01') TO ('2027-01-01');
-- 想定外の日付でも INSERT が失敗しないよう受け皿を用意する
CREATE TABLE time_clock_events_default PARTITION OF time_clock_events DEFAULT;
```

#### `recorded_by` を持つ理由

打刻した本人と、記録を行った人は一致しないことがある（人事による代理登録、訂正の反映など）。
**誰が記録したかを残さないと、不正打刻の調査ができない。**

#### 更新・削除を禁じる運用

テーブル定義だけでは UPDATE / DELETE を防げない。
アプリケーション側では、ポートに追記と参照のメソッドしか置かないことで防ぐ
（ドメインモデル設計書 3 章）。運用者による直接操作の抑止は、
権限設計（`REVOKE UPDATE, DELETE`）で行う。M1-c で扱う。

### 3.2 daily_attendances（日次勤怠）

```sql
CREATE TABLE daily_attendances (
    id                                uuid        PRIMARY KEY,
    employee_id                       uuid        NOT NULL REFERENCES employees (id),
    work_date                         date        NOT NULL,
    day_type                          varchar(20) NOT NULL,
    work_rule_id                      uuid        NOT NULL REFERENCES work_rules (id),
    working_minutes                   int         NOT NULL,
    break_minutes                     int         NOT NULL,
    within_scheduled_minutes          int         NOT NULL,
    overtime_within_statutory_minutes int         NOT NULL,
    overtime_beyond_statutory_minutes int         NOT NULL,
    night_minutes                     int         NOT NULL,
    legal_holiday_minutes             int         NOT NULL,
    break_requirement_satisfied       boolean     NOT NULL DEFAULT true,
    calculated_at                     timestamptz NOT NULL DEFAULT now(),
    version                           bigint      NOT NULL DEFAULT 0,

    CONSTRAINT daily_attendances_day_type_check
        CHECK (day_type IN ('WORKDAY', 'LEGAL_HOLIDAY', 'NON_LEGAL_HOLIDAY')),

    CONSTRAINT daily_attendances_non_negative_check
        CHECK (working_minutes >= 0 AND break_minutes >= 0
               AND within_scheduled_minutes >= 0
               AND overtime_within_statutory_minutes >= 0
               AND overtime_beyond_statutory_minutes >= 0
               AND night_minutes >= 0
               AND legal_holiday_minutes >= 0),

    -- ★ 排他的な 4 区分の合計は実労働時間に一致する。深夜は重ね掛けなので含めない
    CONSTRAINT daily_attendances_breakdown_check
        CHECK (within_scheduled_minutes
               + overtime_within_statutory_minutes
               + overtime_beyond_statutory_minutes
               + legal_holiday_minutes = working_minutes),

    -- ★ 深夜労働が実労働時間を超えることはありえない
    CONSTRAINT daily_attendances_night_within_working_check
        CHECK (night_minutes <= working_minutes),

    -- ★ 法定休日には残業の概念が無い（BR-07）
    CONSTRAINT daily_attendances_legal_holiday_exclusive_check
        CHECK (day_type <> 'LEGAL_HOLIDAY'
               OR (overtime_within_statutory_minutes = 0
                   AND overtime_beyond_statutory_minutes = 0
                   AND within_scheduled_minutes = 0)),

    -- ★ 法定休日以外に法定休日労働が計上されることはありえない
    CONSTRAINT daily_attendances_legal_holiday_only_check
        CHECK (day_type = 'LEGAL_HOLIDAY' OR legal_holiday_minutes = 0),

    CONSTRAINT daily_attendances_employee_date_uk UNIQUE (employee_id, work_date)
);
```

#### CHECK 制約が実際にバグを検出した例

`daily_attendances_breakdown_check` は、開発中に集計ロジックの誤りを検出した実績がある。
「深夜だけが付いた所定内の区間」が所定内労働に数えられておらず、
内訳の合計が実労働時間より小さくなっていた（ドメインモデル設計書 2.4 参照）。

**制約が無ければ、給与計算に渡るまで気づけなかった種類の不具合である。**

### 3.3 daily_attendance_slices（内訳）

```sql
CREATE TABLE daily_attendance_slices (
    id                  uuid        PRIMARY KEY,
    daily_attendance_id uuid        NOT NULL
                                    REFERENCES daily_attendances (id) ON DELETE CASCADE,
    sequence_no         int         NOT NULL,
    started_at          timestamptz NOT NULL,
    ended_at            timestamptz NOT NULL,
    -- 1 区間に複数の割増が重なるため配列で持つ（深夜 かつ 法定外残業 など）
    premiums            text[]      NOT NULL DEFAULT '{}',

    CONSTRAINT daily_attendance_slices_period_check
        CHECK (ended_at > started_at),
    CONSTRAINT daily_attendance_slices_premiums_check
        CHECK (premiums <@ ARRAY['OVERTIME_WITHIN_STATUTORY',
                                 'OVERTIME_BEYOND_STATUTORY',
                                 'NIGHT',
                                 'LEGAL_HOLIDAY']::text[]),
    -- 排他的な区分が 1 区間に 2 つ以上付くことはありえない。
    -- CHECK 制約に副問い合わせは書けないため、配列演算子で数える
    CONSTRAINT daily_attendance_slices_exclusive_premium_check
        CHECK ((CASE WHEN 'OVERTIME_WITHIN_STATUTORY' = ANY (premiums) THEN 1 ELSE 0 END
              + CASE WHEN 'OVERTIME_BEYOND_STATUTORY' = ANY (premiums) THEN 1 ELSE 0 END
              + CASE WHEN 'LEGAL_HOLIDAY'              = ANY (premiums) THEN 1 ELSE 0 END) <= 1),

    CONSTRAINT daily_attendance_slices_order_uk UNIQUE (daily_attendance_id, sequence_no)
);

CREATE INDEX daily_attendance_slices_parent_idx
    ON daily_attendance_slices (daily_attendance_id, sequence_no);
```

`premiums` を配列にするのは、**割増が 1 区間に重なりうる** ためである。
別テーブルへ正規化すると、区間 1 つに対して 0〜2 行という扱いづらい構造になり、
「属性の無い所定内区間」を表現できなくなる。

`daily_attendance_slices_exclusive_premium_check` は、
`PremiumType.partitionsWorkingTime()` が真である区分（所定内以外の 3 つ）が
1 区間に 2 つ以上付かないことを保証する。ドメインの区分の性質を DB でも表現している。

---

## 4. 主要なクエリ

### 4.1 有効な打刻の取得（取り消されていない ENTRY）

```sql
SELECT e.id, e.event_type, e.occurred_at
FROM time_clock_events e
WHERE e.employee_id = :employeeId
  AND e.work_date = :workDate
  AND e.entry_type = 'ENTRY'
  AND NOT EXISTS (
      SELECT 1 FROM time_clock_events r
      WHERE r.work_date = e.work_date
        AND r.entry_type = 'REVOCATION'
        AND r.revokes_event_id = e.id
  )
ORDER BY e.occurred_at;
```

`employee_id + work_date` で絞るためパーティション枝刈りが効く。
**`id` だけで引くクエリを書かない。** 全パーティションを走査してしまうため。

### 4.2 未退勤の勤務日を探す（BR-03）

```sql
SELECT e.work_date
FROM time_clock_events e
WHERE e.employee_id = :employeeId
  AND e.work_date >= :notBefore
  AND e.entry_type = 'ENTRY'
  AND NOT EXISTS (SELECT 1 FROM time_clock_events r
                  WHERE r.work_date = e.work_date AND r.revokes_event_id = e.id)
GROUP BY e.work_date
HAVING sum(CASE WHEN e.event_type = 'CLOCK_OUT' THEN 1 ELSE 0 END) = 0
ORDER BY e.work_date DESC
LIMIT 1;
```

### 4.3 月次の日次勤怠（内訳つき）

```sql
SELECT a.*, s.sequence_no, s.started_at, s.ended_at, s.premiums
FROM daily_attendances a
LEFT JOIN daily_attendance_slices s ON s.daily_attendance_id = a.id
WHERE a.employee_id = :employeeId
  AND a.work_date >= :monthStart AND a.work_date < :nextMonthStart
ORDER BY a.work_date, s.sequence_no;
```

`daily_attendances_employee_date_uk` が生成する `(employee_id, work_date)` の
複合インデックスが効く。**内訳を別クエリで取ると N+1 になるため 1 回で取得する。**

---

## 5. 制約の一覧

| 制約名 | 種類 | 守るもの |
| --- | --- | --- |
| `time_clock_events_revocation_check` | CHECK | 取消行は対象と理由を持ち、通常の打刻は持たない |
| `time_clock_events_revokes_fk` | FK | **取消対象が同じ勤務日に存在する** |
| `time_clock_events_revokes_uk` | UNIQUE | 同じ打刻を二重に取り消さない |
| `daily_attendances_breakdown_check` | CHECK | **内訳の合計 = 実労働時間** |
| `daily_attendances_night_within_working_check` | CHECK | 深夜労働が実労働時間を超えない |
| `daily_attendances_legal_holiday_exclusive_check` | CHECK | **法定休日に残業区分が付かない** |
| `daily_attendances_legal_holiday_only_check` | CHECK | 法定休日以外に法定休日労働が付かない |
| `daily_attendances_employee_date_uk` | UNIQUE | 社員・勤務日の一意性 |
| `daily_attendance_slices_premiums_check` | CHECK | 未知の割増区分を拒否 |
| `daily_attendance_slices_exclusive_premium_check` | CHECK | **排他的な区分が 1 区間に 2 つ以上付かない** |

---

## 6. 制約の検証

| ID | 検証内容 | 期待 | 確認 |
| --- | --- | --- | --- |
| IT-ATT-01 | 打刻がパーティションへ正しく振り分けられる | `work_date` に対応する子テーブルへ入る | 済 |
| IT-ATT-02 | 内訳の合計が実労働時間と一致しない日次勤怠 | `daily_attendances_breakdown_check` で拒否 | 済 |
| IT-ATT-03 | 深夜労働が実労働時間を超える | `night_within_working_check` で拒否 | 済 |
| IT-ATT-04 | 法定休日に法定外残業を計上する | `legal_holiday_exclusive_check` で拒否 | 済 |
| IT-ATT-05 | 所定労働日に法定休日労働を計上する | `legal_holiday_only_check` で拒否 | 済 |
| IT-ATT-06 | 取消行に対象を設定しない | `revocation_check` で拒否 | 済 |
| IT-ATT-07 | 通常の打刻に取消対象を設定する | `revocation_check` で拒否 | 済 |
| IT-ATT-08 | 同じ打刻を二重に取り消す | `revokes_uk` で拒否 | 済 |
| IT-ATT-09 | 未知の割増区分を内訳に登録する | `premiums_check` で拒否 | 済 |
| IT-ATT-10 | 1 区間に法定内残業と法定外残業を同時に付ける | `exclusive_premium_check` で拒否 | 済 |
| IT-ATT-11 | 深夜と法定外残業を同時に付ける | 成功する（重複可のため） | 済 |
| IT-ATT-12 | 有効な打刻の取得クエリが取消済みを除外する | 4.1 が正しい結果を返す | 済 |

---

## 7. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | パーティションの自動生成（`pg_partman` の導入か、年次のマイグレーションか） | M3 |
| 2 | 打刻テーブルへの UPDATE / DELETE を DB 権限で禁止するか | M1-c |
