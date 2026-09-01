# 就業規則・カレンダー DB設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-202 |
| 版 | 0.2 |
| 対象スキーマ | `work_rule_series` / `work_rules` / `work_rule_assignments` / `company_calendars` |
| 関連要件 | [3章 前提条件：就業規則](../../01_要件定義/要件定義書.md#3-前提条件就業規則) |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [API設計書](API設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |
| 改訂 | 0.2（2026-09-01）設計レビュー第 2 回の指摘を反映 |

---

## 1. ER 図

![ER図](images/ER図.png)

<sub>図のソース: [`diagrams/ER図.mmd`](diagrams/ER図.mmd)</sub>

---

## 2. 設計の要点

| # | 設計 | 理由 |
| --- | --- | --- |
| 1 | 就業規則を **系列（`work_rule_series`）と版（`work_rules`）に分ける** | 社員への適用は系列を指す。規則を改定しても適用行を書き換えずに済む（下記 2.1） |
| 2 | 労働時間制度を **1 テーブル + 判別列 + CHECK 制約** で表現する | 制度は 2 種で列数も少ない。ドメインの `sealed interface` と対になる形を DB でも作る |
| 3 | 制度ごとに「必要な列が揃い、不要な列が NULL である」ことを CHECK で保証する | 中途半端な行（FLEX なのに始業時刻が入っている等）を作れなくする |
| 4 | 割増率・法定労働時間・深夜帯の **法定の範囲を CHECK で守る** | 下限だけでなく **上限** も守らないと、脱法的な規則を登録できてしまう（下記 2.2） |
| 5 | 所定労働時間を **生成列**として持ち、法定労働時間・法定休憩と突き合わせる | 始業・終業・休憩から一意に決まる値を、都度計算せずに制約の対象にできる |
| 6 | 会社カレンダーは暦日を主キーとする | 1 日 1 区分。存在しない日は所定労働日として扱う |

### 2.1 なぜ「系列」と「版」を分けるのか

第 1 版では `work_rule_assignments.work_rule_id` が `work_rules.id` を直接指していた。
この形だと **規則を改定した瞬間、全社員の就業規則が「未設定」になる。**

改定は「現行の版の `valid_to` を閉じ、新しい版の行を作る」操作である。
適用行は古い版の `id` を指したままなので、4.1 のクエリが
「適用期間には入っているが、その版はもう有効ではない」として 0 件を返してしまう。
勤怠計算はすべて停止する。

```
（誤）assignments ──> work_rules（版）      改定すると指し先が過去の版になる
（正）assignments ──> work_rule_series ──< work_rules（版）
                                            日付で版を選ぶ
```

**社員が結びつくのは「標準勤務という規則」であって「2024 年 4 月版の標準勤務」ではない。**
系列を指させることで、改定は版を 1 行足すだけの操作になる。

### 2.2 上限を守らないと何が起きるか

`statutory_daily_minutes >= 0` のような下限だけの制約では、
`statutory_daily_minutes = 720`（12 時間）という規則を登録できてしまう。
すると 1 日 12 時間働いても法定外残業が 0 分と計算され、割増賃金が支払われない。

割増**率**の下限（労基法 37 条）は第 1 版から守っていたが、
**割増の対象になる時間そのものを消せる穴**が空いていた。
法定労働時間・深夜帯は「これより労働者に不利にはできない」向きに上限・固定値を置く。

---

## 3. テーブル定義

### 3.0 前提となる拡張

```sql
-- EXCLUDE 制約で uuid の等値比較と範囲型の重なり比較を
-- 1 つの GiST インデックスに同居させるために必要
CREATE EXTENSION IF NOT EXISTS btree_gist;
```

これが無いと `work_rules` / `work_rule_assignments` の `EXCLUDE` 制約が
`data type uuid has no default operator class for access method "gist"` で作成に失敗する。
**Flyway の初回マイグレーションの先頭に置く**（`01_社員・組織` と同じ宣言。重複して書いても
`IF NOT EXISTS` なので害はない）。

### 3.1 work_rule_series（就業規則の系列）

改定をまたいで変わらない識別子。社員への適用はこちらを指す。

```sql
CREATE TABLE work_rule_series (
    id          uuid         PRIMARY KEY,
    name        varchar(100) NOT NULL,
    abolished_on date,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT work_rule_series_name_key UNIQUE (name)
);
```

| カラム | 意味 |
| --- | --- |
| `name` | 「標準勤務」「フレックス勤務」など。改定しても変わらない |
| `abolished_on` | 系列そのものを廃止した日（半開区間の上限。この日から使えない） |
| `version` | 楽観ロック |

### 3.2 work_rules（就業規則の版）

```sql
CREATE TABLE work_rules (
    id                       uuid         PRIMARY KEY,
    series_id                uuid         NOT NULL REFERENCES work_rule_series (id),
    working_time_system      varchar(20)  NOT NULL,
    valid_from               date         NOT NULL,
    valid_to                 date,

    -- 固定時間制のときだけ使う列
    scheduled_start          time,
    scheduled_end            time,
    scheduled_break_minutes  int,

    -- フレックスタイム制のときだけ使う列
    flexible_start           time,
    flexible_end             time,
    core_start               time,
    core_end                 time,
    standard_daily_minutes   int,

    -- 制度によらず共通の列
    statutory_daily_minutes  int          NOT NULL DEFAULT 480,
    statutory_weekly_minutes int          NOT NULL DEFAULT 2400,
    night_start              time         NOT NULL DEFAULT '22:00',
    night_end                time         NOT NULL DEFAULT '05:00',
    rate_overtime            numeric(4,3) NOT NULL DEFAULT 0.250,
    rate_night               numeric(4,3) NOT NULL DEFAULT 0.250,
    rate_legal_holiday       numeric(4,3) NOT NULL DEFAULT 0.350,

    -- ★ 所定労働時間。始業・終業・休憩から一意に決まるので生成列にする。
    --   終業が始業以下なら日をまたぐ勤務とみなして 24 時間を足す
    scheduled_working_minutes int GENERATED ALWAYS AS (
        CASE WHEN working_time_system = 'FIXED' THEN
            (EXTRACT(epoch FROM (scheduled_end - scheduled_start))::int
             + CASE WHEN scheduled_end > scheduled_start THEN 0 ELSE 86400 END) / 60
            - scheduled_break_minutes
        END
    ) STORED,

    version                  bigint       NOT NULL DEFAULT 0,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    updated_at               timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT work_rules_system_check
        CHECK (working_time_system IN ('FIXED', 'FLEX')),
    CONSTRAINT work_rules_validity_check
        CHECK (valid_to IS NULL OR valid_to > valid_from),

    -- ★ 制度ごとに必要な列が揃い、不要な列が NULL であることを保証する。
    --   ドメインの sealed interface（FixedTimeSystem / FlextimeSystem）に対応する制約
    CONSTRAINT work_rules_variant_check CHECK (
        (working_time_system = 'FIXED'
             AND scheduled_start IS NOT NULL
             AND scheduled_end IS NOT NULL
             AND scheduled_break_minutes IS NOT NULL
             AND flexible_start IS NULL AND flexible_end IS NULL
             AND core_start IS NULL AND core_end IS NULL
             AND standard_daily_minutes IS NULL)
        OR
        (working_time_system = 'FLEX'
             AND flexible_start IS NOT NULL
             AND flexible_end IS NOT NULL
             AND core_start IS NOT NULL
             AND core_end IS NOT NULL
             AND standard_daily_minutes IS NOT NULL
             AND scheduled_start IS NULL AND scheduled_end IS NULL
             AND scheduled_break_minutes IS NULL)
    ),

    -- コアタイムはフレキシブルタイムの内側になければならない
    CONSTRAINT work_rules_core_within_flexible_check CHECK (
        working_time_system <> 'FLEX'
        OR (flexible_start <= core_start AND core_start < core_end AND core_end <= flexible_end)
    ),

    -- 労基法 37 条の割増率の法定下限。ドメイン層と同じ不変条件を DB にも置く
    CONSTRAINT work_rules_rate_overtime_check      CHECK (rate_overtime >= 0.250),
    CONSTRAINT work_rules_rate_night_check         CHECK (rate_night >= 0.250),
    CONSTRAINT work_rules_rate_legal_holiday_check CHECK (rate_legal_holiday >= 0.350),

    -- ★ 労基法 32 条の法定労働時間。原則を上回る値を「法定」として登録させない
    CONSTRAINT work_rules_statutory_daily_check
        CHECK (statutory_daily_minutes > 0 AND statutory_daily_minutes <= 480),
    CONSTRAINT work_rules_statutory_weekly_check
        CHECK (statutory_weekly_minutes > 0 AND statutory_weekly_minutes <= 2400),
    CONSTRAINT work_rules_statutory_consistency_check
        CHECK (statutory_daily_minutes <= statutory_weekly_minutes),

    -- ★ 労基法 37 条 4 項の深夜帯。原則 22:00–05:00。
    --   厚生労働大臣が定める地域の 23:00–06:00 だけを例外として許す
    CONSTRAINT work_rules_night_window_check CHECK (
        (night_start, night_end) IN ((time '22:00', time '05:00'),
                                     (time '23:00', time '06:00'))
    ),

    -- ★ 所定労働時間は法定労働時間を超えられない（超えるなら 36 協定と割増が必要で、
    --   それは「所定」ではなく残業である）
    CONSTRAINT work_rules_scheduled_within_statutory_check CHECK (
        scheduled_working_minutes IS NULL
        OR (scheduled_working_minutes > 0
            AND scheduled_working_minutes <= statutory_daily_minutes)
    ),

    -- ★ 労基法 34 条の休憩。所定労働時間が 6 時間を超えるなら 45 分以上
    CONSTRAINT work_rules_break_statutory_check CHECK (
        scheduled_working_minutes IS NULL
        OR scheduled_break_minutes >= CASE
               WHEN scheduled_working_minutes > 360 THEN 45
               ELSE 0 END
    ),

    -- フレックスの 1 日あたりの係数も法定労働時間を超えられない
    CONSTRAINT work_rules_standard_daily_check
        CHECK (standard_daily_minutes IS NULL
               OR (standard_daily_minutes > 0
                   AND standard_daily_minutes <= statutory_daily_minutes)),

    -- ★ 同じ系列の版の有効期間が重複しない（改定は期間を区切って行う）
    CONSTRAINT work_rules_no_overlapping_versions
        EXCLUDE USING gist (
            series_id WITH =,
            daterange(valid_from, valid_to, '[)') WITH &&
        )
);
```

#### `work_rules_variant_check` について

これは **ドメインの `sealed interface` を DB 側で表現したもの** である。

```java
sealed interface WorkingTimeSystem permits FixedTimeSystem, FlextimeSystem
```

Java 側では、フレックスの規則から `scheduledStart` を読もうとしてもコンパイルが通らない。
同じ保証を DB でも得るために、制度ごとの列の充足を制約として書いている。

これが無いと「`working_time_system = 'FLEX'` なのに `scheduled_start` に値が入っている」
という行が作れてしまい、どちらを信じればよいか分からないデータが生まれる。

#### 生成列 `scheduled_working_minutes` について

所定労働時間は始業・終業・休憩から一意に決まる。
アプリケーションが計算して別列に入れる形にすると、
**式を書き間違えた行や、片方だけ更新された行**が生まれる。

生成列にすると DB が式を保持するので、
`work_rules_scheduled_within_statutory_check` と
`work_rules_break_statutory_check` がその値を直接参照できる。

> 日をまたぐ固定勤務（22:00–06:00）では `scheduled_end <= scheduled_start` になる。
> このとき 86400 秒を足して翌日の終業として扱う。
> ドメインの `FixedTimeSystem.scheduledWorkingTime()` と同じ規則である。

#### 休憩の制約に「8 時間超なら 60 分」を書かない理由

労基法 34 条は 6 時間超で 45 分、**8 時間超で 60 分**を求める。
しかし `work_rules_scheduled_within_statutory_check` により
`scheduled_working_minutes <= statutory_daily_minutes <= 480` なので、
**所定労働時間が 8 時間を超える行はそもそも作れない。**
条件を書いても永遠に成立しないため、書かない。

8 時間超の判定が必要になるのは **実労働時間** に対してである。
所定 8 時間・休憩 45 分の規則で 1 分でも残業すれば 60 分の休憩が要る。
これは日次の実績に対する規則なので
[03_勤怠_打刻と日次集計](../03_勤怠_打刻と日次集計/ドメインモデル設計書.md) で扱う。

### 3.3 work_rule_assignments（社員への適用）

```sql
CREATE TABLE work_rule_assignments (
    id                  uuid        PRIMARY KEY,
    employee_id         uuid        NOT NULL REFERENCES employees (id),
    work_rule_series_id uuid        NOT NULL REFERENCES work_rule_series (id),
    valid_from          date        NOT NULL,
    valid_to            date,
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT work_rule_assignments_period_check
        CHECK (valid_to IS NULL OR valid_to > valid_from),

    -- ★ 1 人の社員に、ある日付で適用される就業規則は必ず 0 件か 1 件
    CONSTRAINT work_rule_assignments_no_overlap
        EXCLUDE USING gist (
            employee_id WITH =,
            daterange(valid_from, valid_to, '[)') WITH &&
        )
);
```

**適用は系列（`work_rule_series_id`）を指す。** 版（`work_rules.id`）ではない。
理由は 2.1 のとおり。

**期間はすべて半開区間 `[valid_from, valid_to)`。**
`valid_to` の日には既に次の規則が適用されている。

> **`(employee_id, valid_from DESC)` の B-tree は置かない。**
> `EXCLUDE` が生成する GiST インデックスが同じ絞り込みを担う。
> 重ねて置くと書き込みコストだけが増える。

### 3.4 適用開始日の検証（制約トリガ）

適用開始日は **月初日、または当該社員の入社日** に限る。

```sql
CREATE OR REPLACE FUNCTION work_rule_assignments_validate() RETURNS trigger AS $$
DECLARE
    emp_hired_on    date;
    emp_retired_on  date;
    series_abolished date;
BEGIN
    SELECT hired_on, retired_on INTO emp_hired_on, emp_retired_on
      FROM employees WHERE id = NEW.employee_id;

    IF NEW.valid_from < emp_hired_on THEN
        RAISE EXCEPTION '入社日より前に就業規則は適用できません (valid_from=%, hired_on=%)',
            NEW.valid_from, emp_hired_on;
    END IF;

    -- ★ 月初日でなくてよいのは、入社日そのものだけ
    IF NEW.valid_from <> date_trunc('month', NEW.valid_from)::date
       AND NEW.valid_from <> emp_hired_on THEN
        RAISE EXCEPTION '就業規則の適用開始日は月初日か入社日に限ります (valid_from=%)',
            NEW.valid_from;
    END IF;

    IF emp_retired_on IS NOT NULL AND NEW.valid_from > emp_retired_on THEN
        RAISE EXCEPTION '退職済みの社員に就業規則は適用できません (employee_id=%)',
            NEW.employee_id;
    END IF;

    SELECT abolished_on INTO series_abolished
      FROM work_rule_series WHERE id = NEW.work_rule_series_id;
    IF series_abolished IS NOT NULL AND NEW.valid_from >= series_abolished THEN
        RAISE EXCEPTION '廃止済みの就業規則は適用できません (series_id=%)',
            NEW.work_rule_series_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER work_rule_assignments_validate_trigger
    AFTER INSERT OR UPDATE ON work_rule_assignments
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION work_rule_assignments_validate();
```

#### なぜ「月初日のみ」ではいけないのか

第 1 版は適用開始日を月初日に限定していた。すると **月の途中で入社した社員に
就業規則を適用できない。** 4 月 15 日入社の社員は、5 月 1 日まで規則が無い状態になり、
4 月分の勤怠が一切計算できず、初月を締められない。

入社日を例外として許すと、フレックスの清算期間はどうなるか。
**初月だけ「入社日から月末まで」を清算期間とする。**
法定労働時間の総枠も、暦月ではなく在籍期間の暦日数で計算する
（[04_勤怠_月次清算](../04_勤怠_月次清算/ドメインモデル設計書.md) の在籍期間の扱いと同じ）。

制度の途中変更（固定 → フレックス）を月初日に限る理由は変わらない。
清算期間の途中で制度が変わると、その月の総労働時間をどちらの制度で判定するか決められない。
**入社は「変更」ではなく「開始」なので、この理由が当てはまらない。**

### 3.5 company_calendars（会社カレンダー）

```sql
CREATE TABLE company_calendars (
    calendar_date date         PRIMARY KEY,
    day_type      varchar(20)  NOT NULL,
    name          varchar(100),
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT company_calendars_day_type_check
        CHECK (day_type IN ('WORKDAY', 'LEGAL_HOLIDAY', 'NON_LEGAL_HOLIDAY'))
);

CREATE INDEX company_calendars_day_type_idx ON company_calendars (day_type, calendar_date);
```

`name` は「元日」「創立記念日」など、休日の名称を保持する。画面表示に使う。

> **週に 1 日の法定休日が確保されているか（労基法 35 条）は DB では守れない。**
> 「連続 7 日すべてが `WORKDAY`」という状態を作れてしまう。
> 一括設定 API の応答で警告として返す（[API設計書 3.2](API設計書.md)）。

### 3.6 updated_at の自動更新

`DEFAULT now()` は INSERT 時にしか効かない。UPDATE でも更新されるようトリガを置く。
`set_updated_at()` の定義は
[01_社員・組織 DB設計書 3.8](../01_社員・組織/DB設計書.md) にある。

```sql
CREATE TRIGGER work_rule_series_set_updated_at BEFORE UPDATE ON work_rule_series
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER work_rules_set_updated_at BEFORE UPDATE ON work_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER work_rule_assignments_set_updated_at BEFORE UPDATE ON work_rule_assignments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER company_calendars_set_updated_at BEFORE UPDATE ON company_calendars
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

---

## 4. 主要なクエリ

### 4.1 指定日に適用される就業規則

```sql
SELECT r.*
FROM work_rule_assignments a
JOIN work_rule_series s ON s.id = a.work_rule_series_id
JOIN work_rules r       ON r.series_id = s.id
WHERE a.employee_id = :employeeId
  AND a.valid_from <= :date AND (a.valid_to IS NULL OR a.valid_to > :date)
  AND r.valid_from <= :date AND (r.valid_to IS NULL OR r.valid_to > :date);
```

**適用（系列）を先に決め、そのうえで指定日に有効な版を選ぶ。**
版を直接指していた第 1 版では、改定した瞬間に 0 件になっていた（2.1）。

`work_rule_assignments_no_overlap` と `work_rules_no_overlapping_versions` の 2 つの
排他制約により、結果は高々 1 件になる。

### 4.2 指定月の所定労働日数（フレックスの所定総労働時間の算出に使う）

```sql
SELECT count(*) AS workday_count
FROM generate_series(:periodStart::date, :periodEndExclusive::date - 1, INTERVAL '1 day')
         AS d(calendar_date)
LEFT JOIN company_calendars c ON c.calendar_date = d.calendar_date::date
WHERE coalesce(c.day_type, 'WORKDAY') = 'WORKDAY';
```

`generate_series` で暦日を作り、カレンダーに **登録が無い日は所定労働日** として数える
（`coalesce`）。ドメインの `CompanyCalendar.dayTypeOf` と同じ既定値の扱いである。

**期間を半開区間 `[periodStart, periodEndExclusive)` で受ける。**
月中入社の初月は「入社日から翌月 1 日まで」になるため、
月初日固定にできない（3.4）。`generate_series` は閉区間しか受けないので、
上限から 1 日引いて渡す。

### 4.3 指定期間の暦日区分をまとめて取得

```sql
SELECT calendar_date, day_type, name
FROM company_calendars
WHERE calendar_date >= :from AND calendar_date < :toExclusive;
```

月次の集計で日ごとに問い合わせると N+1 になるため、まとめて取得してメモリ上で引く。

### 4.4 所定総労働時間が法定労働時間の総枠を超える月の検出

フレックスでは `standard_daily_minutes × 所定労働日数` が清算期間の所定総労働時間になる。
これが **法定労働時間の総枠（暦日数 ÷ 7 × 週法定労働時間）を超える月がある。**

```sql
WITH period AS (
    SELECT :periodStart::date AS start_date, :periodEndExclusive::date AS end_date
), workdays AS (
    SELECT count(*) AS workday_count
    FROM period p,
         generate_series(p.start_date, p.end_date - 1, INTERVAL '1 day') AS d(calendar_date)
    LEFT JOIN company_calendars c ON c.calendar_date = d.calendar_date::date
    WHERE coalesce(c.day_type, 'WORKDAY') = 'WORKDAY'
)
SELECT w.workday_count,
       r.standard_daily_minutes * w.workday_count AS scheduled_total_minutes,
       (p.end_date - p.start_date) * r.statutory_weekly_minutes / 7 AS statutory_total_limit_minutes,
       r.standard_daily_minutes * w.workday_count
           > (p.end_date - p.start_date) * r.statutory_weekly_minutes / 7 AS exceeds_limit
FROM period p, workdays w, work_rules r
WHERE r.id = :workRuleId;
```

**例**: 2026 年 6 月（暦日 30 日、所定労働日 22 日、1 日 8 時間）

| 項目 | 値 |
| --- | --- |
| 所定総労働時間 | 22 日 × 480 分 = **10,560 分**（176 時間） |
| 法定労働時間の総枠 | 30 ÷ 7 × 2,400 = **10,285 分**（171 時間 25 分） |
| 差 | **275 分の超過** |

この月は **所定どおり働くだけで法定外残業が 275 分発生する。**
違法ではないが、36 協定の締結と割増賃金の支払いが必要になる。

[04_勤怠_月次清算](../04_勤怠_月次清算/DB設計書.md) の計算式
`overtime = max(0, 対象労働時間 − 法定総枠)` はこれを正しく残業として扱うため、
**賃金の取りこぼしは起きない。** しかし人事が意図せず
この状態の規則を作ってしまうことは防ぎたいので、
規則の登録・改定時とカレンダーの一括設定時に、このクエリで検出して警告を返す
（[API設計書 2.2 / 3.2](API設計書.md)）。

> **CHECK 制約にはできない。** 所定労働日数はカレンダーに依存し、
> `CHECK` は他テーブルを参照できないため。制約トリガにもしない
> ―― カレンダーを 1 日変えるだけで全規則を再検証することになり、
> 一括設定が現実的な時間で終わらなくなる。

---

## 5. 制約の一覧

| 制約名 | 種類 | 守るもの |
| --- | --- | --- |
| `work_rule_series_name_key` | UNIQUE | 系列名が重複しない |
| `work_rules_system_check` | CHECK | 労働時間制度が定義された 2 種のいずれか |
| `work_rules_variant_check` | CHECK | **制度ごとに必要な列が揃い、不要な列が NULL** |
| `work_rules_core_within_flexible_check` | CHECK | コアタイムがフレキシブルタイムの内側にある |
| `work_rules_rate_*_check` | CHECK | **割増率が労基法 37 条の下限以上** |
| `work_rules_statutory_daily_check` | CHECK | **法定労働時間（日）が 8 時間を超えない** |
| `work_rules_statutory_weekly_check` | CHECK | **法定労働時間（週）が 40 時間を超えない** |
| `work_rules_statutory_consistency_check` | CHECK | 日の法定労働時間が週を超えない |
| `work_rules_night_window_check` | CHECK | **深夜帯が 22:00–05:00 または 23:00–06:00** |
| `work_rules_scheduled_within_statutory_check` | CHECK | **所定労働時間が法定労働時間を超えない** |
| `work_rules_break_statutory_check` | CHECK | **所定 6 時間超なら休憩 45 分以上（労基法 34 条）** |
| `work_rules_standard_daily_check` | CHECK | フレックスの係数が法定労働時間を超えない |
| `work_rules_no_overlapping_versions` | EXCLUDE | **同じ系列の版の有効期間が重複しない** |
| `work_rule_assignments_no_overlap` | EXCLUDE | **1 社員に同時に 2 つの就業規則が適用されない** |
| `work_rule_assignments_validate_trigger` | 制約 TRIGGER | 入社日以降・月初日か入社日・退職者でない・廃止系列でない |
| `company_calendars_day_type_check` | CHECK | 暦日区分が定義された 3 種のいずれか |
| `*_set_updated_at` | TRIGGER | `updated_at` の自動更新 |

### 5.1 DB では防げないもの

| 内容 | 守る場所 |
| --- | --- |
| 系列の版に**隙間が無い**こと（隙間の日は「規則未設定」になる） | アプリケーション（改定 API が現行版の `valid_to` を新版の `valid_from` で閉じる） |
| 在籍中の社員全員に就業規則が適用されていること | アプリケーション（社員登録時に既定の系列を適用）+ 未適用者の一覧画面 |
| 所定総労働時間が法定総枠を超えないこと | アプリケーション（4.4 のクエリで警告。カレンダー依存のため CHECK にできない） |
| 週に 1 日の法定休日が確保されていること（労基法 35 条） | アプリケーション（カレンダー一括設定時に警告） |
| 系列を廃止したときに適用行を閉じること | アプリケーション（廃止 API の副作用） |
| 締め済みの月のカレンダー・規則を変更しないこと | アプリケーション（`MonthClosureQuery` ポートで判定） |

**黙って抜けているのが最も危険なので、DB で守れない範囲を明示する。**

---

## 6. インデックス設計

| インデックス | 対象 | 用途 |
| --- | --- | --- |
| `work_rule_assignments_no_overlap`（GiST・EXCLUDE が生成） | `(employee_id, daterange)` | 指定日の適用を引く（4.1） |
| `work_rules_no_overlapping_versions`（GiST・EXCLUDE が生成） | `(series_id, daterange)` | 指定日の版を引く（4.1） |
| `work_rule_series_name_key`（UNIQUE が生成） | `(name)` | 系列名の重複検査・名前引き |
| `company_calendars` の主キー | `(calendar_date)` | 期間の範囲検索（4.2 / 4.3） |
| `company_calendars_day_type_idx` | `(day_type, calendar_date)` | 休日の一覧表示 |

**期間の絞り込みは `EXCLUDE` が生成する GiST インデックスが担う。**
同じ列に B-tree を重ねて置かない。書き込みコストだけが増えるため。

---

## 7. 制約の検証

**検証環境**: PostgreSQL 16 / `btree_gist` 有効 / 2026-09-01 実施

| ID | 検証内容 | 期待 | 結果 |
| --- | --- | --- | --- |
| IT-WR-01 | `FLEX` なのに `scheduled_start` を設定する | `work_rules_variant_check` で拒否 | 済 |
| IT-WR-02 | `FIXED` なのに `core_start` を設定する | `work_rules_variant_check` で拒否 | 済 |
| IT-WR-03 | `FLEX` で `core_start` を欠く | `work_rules_variant_check` で拒否 | 済 |
| IT-WR-04 | コアタイムがフレキシブルタイムの外にある | `work_rules_core_within_flexible_check` で拒否 | 済 |
| IT-WR-05 | 法定外残業の割増率を 0.100 にする | `work_rules_rate_overtime_check` で拒否 | 済 |
| IT-WR-06 | **法定労働時間（日）を 720 分にする** | `work_rules_statutory_daily_check` で拒否 | 済 |
| IT-WR-07 | **深夜帯を 02:00–03:00 にする** | `work_rules_night_window_check` で拒否 | 済 |
| IT-WR-08 | **所定 9 時間（09:00–19:00 / 休憩 60 分）の規則** | `work_rules_scheduled_within_statutory_check` で拒否 | 済 |
| IT-WR-09 | **所定 7 時間（09:00–16:30）なのに休憩 30 分** | `work_rules_break_statutory_check` で拒否 | 済 |
| IT-WR-10 | 同じ系列に期間の重なる版を作る | `work_rules_no_overlapping_versions` で拒否 | 済 |
| IT-WR-11 | 別系列なら同じ期間の版を作れる | 成功する | 済 |
| IT-WR-12 | 同一社員に期間の重なる適用を登録する | `work_rule_assignments_no_overlap` で拒否 | 済 |
| IT-WR-13 | **適用開始日を月初日でも入社日でもない日にする** | 制約トリガで拒否 | 済 |
| IT-WR-14 | **月中入社の社員に、入社日から適用する** | 成功する | 済 |
| IT-WR-15 | 入社日より前から適用する | 制約トリガで拒否 | 済 |
| IT-WR-16 | 退職済みの社員に適用する | 制約トリガで拒否 | 済 |
| IT-WR-17 | 未定義の暦日区分を登録する | `company_calendars_day_type_check` で拒否 | 済 |
| IT-WR-18 | 日をまたぐ固定勤務（22:00–06:00 / 休憩 60 分） | `scheduled_working_minutes` が 420 になる | 済 |
| IT-WR-19 | **改定後も 4.1 が規則を返す** | 版を 1 行足しても適用は切れない | 済 |
| IT-WR-20 | 所定労働日数のクエリが未登録日を所定労働日として数える | 4.2 が正しい件数を返す | 済 |
| IT-WR-21 | **2026 年 6 月・所定 22 日・1 日 8 時間** | 4.4 が `exceeds_limit = true`（10,560 > 10,285）を返す | 済 |
| IT-WR-22 | `updated_at` が UPDATE で更新される | トリガにより現在時刻になる | 済 |

**IT-WR-19 が今回のレビューで最も重要な検証である。**
第 1 版のスキーマでは、この検証を書いていれば
「改定すると全社員の規則が消える」欠陥に設計段階で気づけた。

---

## 8. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 祝日データの投入方法（内閣府 CSV の取込か手動登録か） | M1-a の実装時 |
| 2 | 就業規則の改定時に過去分を再計算するか | 05_申請承認と締め の設計時 |
| 3 | 系列の版に隙間ができたことを検出する定期チェックを設けるか | 運用設計時 |
