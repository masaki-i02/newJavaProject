# 勤怠（月次清算） DB設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-402 |
| 版 | 0.1 |
| 対象スキーマ | `monthly_settlements` / `weekly_overtimes` |
| 関連要件 | BR-04 / BR-05 / BR-12 |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) |

---

## 1. ER 図

![ER図](images/ER図.png)

<sub>図のソース: [`diagrams/ER図.mmd`](diagrams/ER図.mmd)</sub>

---

## 2. 設計の要点

| # | 設計 | 理由 |
| --- | --- | --- |
| 1 | 月次清算の結果を**保存する**（都度計算しない） | 締めた後に就業規則を改定しても確定値が動いてはいけない |
| 2 | 労働時間制度ごとに **CHECK 制約で列の充足を変える** | 固定時間制とフレックスでは意味を持つ列が異なる。ドメインの `sealed interface` に対応させる |
| 3 | 算出式そのものを CHECK 制約にする | 対象労働時間・時間外労働の導出を DB でも検証し、集計ロジックの誤りを通さない |
| 4 | 週 40 時間超の内訳を別テーブルに持つ | どの週で何時間超えたかを提示できないと、労務の問い合わせに答えられない |
| 5 | 年度累計を列として持つ | 36 協定の年次上限の判定に、当月より前の累計が必要。毎回全月を再集計しない |

---

## 3. テーブル定義

### 3.1 monthly_settlements（月次清算）

```sql
CREATE TABLE monthly_settlements (
    id                              uuid        PRIMARY KEY,
    employee_id                     uuid        NOT NULL REFERENCES employees (id),
    target_month                    date        NOT NULL,
    work_rule_id                    uuid        NOT NULL REFERENCES work_rules (id),
    working_time_system             varchar(20) NOT NULL,

    working_minutes                 int         NOT NULL,
    legal_holiday_minutes           int         NOT NULL,
    target_working_minutes          int         NOT NULL,
    scheduled_total_minutes         int         NOT NULL,
    statutory_total_limit_minutes   int         NOT NULL,

    daily_overtime_minutes          int         NOT NULL DEFAULT 0,
    weekly_overtime_minutes         int         NOT NULL DEFAULT 0,
    overtime_minutes                int         NOT NULL,
    shortage_minutes                int         NOT NULL DEFAULT 0,
    night_minutes                   int         NOT NULL,
    core_time_absence_minutes       int         NOT NULL DEFAULT 0,

    annual_overtime_before_minutes  int         NOT NULL DEFAULT 0,
    exceeds_monthly_agreement_limit boolean     NOT NULL,
    exceeds_annual_agreement_limit  boolean     NOT NULL,

    calculated_at                   timestamptz NOT NULL,
    version                         bigint      NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),

    -- 対象月は必ず月初日で表現する（月の表現ゆらぎを DB で封じる）
    CONSTRAINT monthly_settlements_month_check
        CHECK (target_month = date_trunc('month', target_month)::date),
    CONSTRAINT monthly_settlements_system_check
        CHECK (working_time_system IN ('FIXED', 'FLEX')),

    CONSTRAINT monthly_settlements_non_negative_check
        CHECK (working_minutes >= 0 AND legal_holiday_minutes >= 0
               AND target_working_minutes >= 0 AND scheduled_total_minutes >= 0
               AND statutory_total_limit_minutes > 0
               AND daily_overtime_minutes >= 0 AND weekly_overtime_minutes >= 0
               AND overtime_minutes >= 0 AND shortage_minutes >= 0
               AND night_minutes >= 0 AND core_time_absence_minutes >= 0
               AND annual_overtime_before_minutes >= 0),

    -- ★ 対象労働時間 = 実労働 − 法定休日労働（BR-05）
    CONSTRAINT monthly_settlements_target_working_check
        CHECK (target_working_minutes = working_minutes - legal_holiday_minutes),

    -- ★ 深夜労働が実労働を超えることはありえない
    CONSTRAINT monthly_settlements_night_check
        CHECK (night_minutes <= working_minutes),

    -- ★ 労働時間制度ごとの算出式。ドメインの sealed interface に対応する
    CONSTRAINT monthly_settlements_variant_check CHECK (
        (working_time_system = 'FIXED'
             AND overtime_minutes = daily_overtime_minutes + weekly_overtime_minutes
             AND shortage_minutes = 0
             AND core_time_absence_minutes = 0)
        OR
        (working_time_system = 'FLEX'
             AND daily_overtime_minutes = 0
             AND weekly_overtime_minutes = 0
             AND overtime_minutes = greatest(0, target_working_minutes - statutory_total_limit_minutes)
             AND shortage_minutes = greatest(0, scheduled_total_minutes - target_working_minutes))
    ),

    -- ★ 時間外労働と不足時間は同時に発生しない
    CONSTRAINT monthly_settlements_exclusive_check
        CHECK (overtime_minutes = 0 OR shortage_minutes = 0),

    CONSTRAINT monthly_settlements_employee_month_uk UNIQUE (employee_id, target_month)
);

CREATE INDEX monthly_settlements_month_idx ON monthly_settlements (target_month, employee_id);
CREATE INDEX monthly_settlements_agreement_idx
    ON monthly_settlements (target_month)
    WHERE exceeds_monthly_agreement_limit OR exceeds_annual_agreement_limit;
```

#### `monthly_settlements_variant_check` について

**算出式そのものを制約にしている。**
フレックスの時間外労働は `max(0, 対象労働時間 − 総枠)` であり、
これは計算するまでもなく他の列から決まる。
制約にしておけば、集計ロジックが壊れたときに **DB へ到達する前に落ちる。**

固定時間制では `daily + weekly`、フレックスでは総枠との比較というように、
**制度によって意味を持つ列が異なる** ことも同時に表現している。

`monthly_settlements_agreement_idx` は部分インデックスで、
**36 協定の超過者だけを人事が抽出する** 用途に使う。
超過者は全体の数 % なので、部分インデックスが小さく保たれる。

### 3.2 weekly_overtimes（週 40 時間超の内訳）

```sql
CREATE TABLE weekly_overtimes (
    id                      uuid PRIMARY KEY,
    monthly_settlement_id   uuid NOT NULL
                            REFERENCES monthly_settlements (id) ON DELETE CASCADE,
    week_start              date NOT NULL,
    week_end                date NOT NULL,
    statutory_inside_minutes int NOT NULL,
    overtime_minutes         int NOT NULL,

    -- 週は必ず 7 日間
    CONSTRAINT weekly_overtimes_span_check
        CHECK (week_end = week_start + 6),
    -- 起算日は日曜（法定休日と週の区切りを揃える）
    CONSTRAINT weekly_overtimes_start_dow_check
        CHECK (extract(isodow FROM week_start) = 7),
    CONSTRAINT weekly_overtimes_non_negative_check
        CHECK (statutory_inside_minutes >= 0 AND overtime_minutes >= 0),
    -- ★ 40 時間を超えた分が時間外。算出式を制約にする
    CONSTRAINT weekly_overtimes_calculation_check
        CHECK (overtime_minutes = greatest(0, statutory_inside_minutes - 2400)),

    CONSTRAINT weekly_overtimes_week_uk UNIQUE (monthly_settlement_id, week_start)
);
```

> **`2400`（40 時間）をリテラルで書いている点について**
> 法定労働時間は就業規則の設定項目（`work_rules.statutory_weekly_minutes`）だが、
> `CHECK` 制約は他テーブルを参照できない。
> 現時点では法定どおり 40 時間で固定されているため制約に直書きしているが、
> **週の法定労働時間を変更可能にするなら、この制約は外して制約トリガに変える必要がある。**
> 未決事項 #1 として記録する。

---

## 4. 主要なクエリ

### 4.1 指定月の清算結果（週の内訳つき）

```sql
SELECT s.*, w.week_start, w.week_end, w.statutory_inside_minutes, w.overtime_minutes
FROM monthly_settlements s
LEFT JOIN weekly_overtimes w ON w.monthly_settlement_id = s.id
WHERE s.employee_id = :employeeId AND s.target_month = :month
ORDER BY w.week_start;
```

### 4.2 年度累計の時間外労働（36 協定の年次上限）

```sql
SELECT coalesce(sum(s.overtime_minutes + s.legal_holiday_minutes), 0) AS annual_minutes
FROM monthly_settlements s
WHERE s.employee_id = :employeeId
  AND s.target_month >= :fiscalYearStart
  AND s.target_month < :targetMonth;
```

対象月より **前** の月だけを合計する。当月分は計算中のため含めない。
結果を `annual_overtime_before_minutes` に保存し、再計算時の再集計を避ける。

### 4.3 36 協定の超過者一覧（人事向け）

```sql
SELECT s.employee_id, e.employee_number, e.name,
       s.overtime_minutes, s.legal_holiday_minutes,
       s.exceeds_monthly_agreement_limit, s.exceeds_annual_agreement_limit
FROM monthly_settlements s
JOIN employees e ON e.id = s.employee_id
WHERE s.target_month = :month
  AND (s.exceeds_monthly_agreement_limit OR s.exceeds_annual_agreement_limit)
ORDER BY (s.overtime_minutes + s.legal_holiday_minutes) DESC;
```

`monthly_settlements_agreement_idx`（部分インデックス）が効く。

---

## 5. 制約の一覧

| 制約名 | 種類 | 守るもの |
| --- | --- | --- |
| `monthly_settlements_month_check` | CHECK | 対象月が月初日で表現される |
| `monthly_settlements_target_working_check` | CHECK | **対象労働時間 = 実労働 − 法定休日労働** |
| `monthly_settlements_variant_check` | CHECK | **制度ごとの算出式と、意味を持つ列の充足** |
| `monthly_settlements_exclusive_check` | CHECK | **時間外労働と不足時間が同時に発生しない** |
| `monthly_settlements_night_check` | CHECK | 深夜が実労働を超えない |
| `monthly_settlements_employee_month_uk` | UNIQUE | 社員・対象月の一意性 |
| `weekly_overtimes_span_check` | CHECK | 週が 7 日間 |
| `weekly_overtimes_start_dow_check` | CHECK | 週の起算日が日曜 |
| `weekly_overtimes_calculation_check` | CHECK | **40 時間を超えた分が時間外** |

### 5.1 DB では防げないもの

| 内容 | 守る場所 |
| --- | --- |
| `statutory_total_limit_minutes` が暦日数から正しく算出されていること | ドメイン（`CHECK` からは対象月の暦日数を参照できない） |
| `working_minutes` が日次勤怠の合計と一致すること | アプリケーション（別テーブルの集計は `CHECK` で書けない） |
| `annual_overtime_before_minutes` が過去月の合計と一致すること | アプリケーション |

前 2 つはドメインの不変条件と結合テストで検証する。

---

## 6. 制約の検証

**検証環境**: PostgreSQL 16 / 2026-09-01 実施

| ID | 検証内容 | 期待 | 結果 |
| --- | --- | --- | --- |
| IT-SET-01 | 対象月を月初日以外で登録 | `month_check` で拒否 | 済 |
| IT-SET-02 | 対象労働時間が実労働 − 法定休日と一致しない | `target_working_check` で拒否 | 済 |
| IT-SET-03 | `FIXED` で時間外が日次 + 週次と一致しない | `variant_check` で拒否 | 済 |
| IT-SET-04 | `FIXED` に不足時間を設定 | `variant_check` で拒否 | 済 |
| IT-SET-05 | `FLEX` に日次残業を設定 | `variant_check` で拒否 | 済 |
| IT-SET-06 | `FLEX` で時間外が総枠超過分と一致しない | `variant_check` で拒否 | 済 |
| IT-SET-07 | 時間外と不足を同時に設定 | `exclusive_check` で拒否 | 済 |
| IT-SET-08 | 深夜が実労働を超える | `night_check` で拒否 | 済 |
| IT-SET-09 | 週の起算日が日曜でない | `start_dow_check` で拒否 | 済 |
| IT-SET-10 | 週が 7 日間でない | `span_check` で拒否 | 済 |
| IT-SET-11 | 週の時間外が 40 時間超過分と一致しない | `calculation_check` で拒否 | 済 |
| IT-SET-12 | 正常な `FIXED` / `FLEX` の清算結果 | 成功する | 済 |

---

## 7. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 週の法定労働時間を設定可能にするか（現在は制約に 40 時間を直書き） | M1-b の実装時 |
| 2 | 週の起算曜日を設定可能にするか（現在は日曜固定） | M1-b の実装時 |
| 3 | 月をまたぐ週の計上先（現在は末日基準）を要件に明記する | 要件のレビュー時 |
