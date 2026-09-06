# 給与連携 DB設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-702 |
| 版 | 0.2 |
| 対象スキーマ | `payroll_exports` / `payroll_export_targets` |
| 関連要件 | BR-18 |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [API設計書](API設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |
| 改訂 | 0.2（2026-09-06）対象社員を行として残す形に変更。件数の列を落とした |

---

## 1. 設計の要点

| # | 設計 | 理由 |
| --- | --- | --- |
| 1 | **出力の記録しか持たない。時間の値は写さない** | 出力する値はすべて `monthly_settlements` にある。写すと、締め済みの値が動かないという前提が 2 か所に分かれる |
| 2 | **CSV の本文を保存しない** | 締め済みの月の値は動かないので、対象社員が固定されていれば同じ内容を作り直せる。保存すると全社員の賃金データの複製が増える |
| 3 | **対象社員は行として残す**（`payroll_export_targets`） | 値が動かないことと、**行の集合が動かないこと**は別である。記録のあとに残りの社員を締めると、同じ記録から取得した CSV の行数が増える（ドメインモデル設計書 5.1）|
| 4 | **件数の列を持たない** | 対象社員の行から数える。2 か所に持つと食い違う（落とし穴 39）|
| 5 | 「出力済み」を月次勤怠に書き戻さない | 締めと二重に管理することになる。記録は監査のためであり、再出力の可否を左右しない |
| 6 | **出力に使った 1 か月平均所定労働時間数を記録する** | 締めていない月のカレンダーは変えられるので年度の値は動く。記録が無いと、支払済みの割増賃金の分母を再現できない（労基則 19 条 1 項 4 号）|
| 7 | `exported_at` は **DB の `now()`** | 監査の時刻は偽装できてはならない（CLAUDE.md「時刻の生成元」）|
| 8 | 版（楽観ロック）を持たない | 追記専用であり、更新も削除もしない |

---

## 2. テーブル定義

### 2.1 payroll_exports（出力の記録）

```sql
CREATE TABLE payroll_exports (
    id            uuid        PRIMARY KEY,
    -- ★ 対象月。月初日で持つ（monthly_settlements.target_month と同じ表現）
    target_month  date        NOT NULL,
    -- ★ 実行した人事担当。社員番号ではなく ID で持つ。
    --   社員番号は退職者のぶんが再利用されるので、後から別人を指す
    exported_by   uuid        NOT NULL REFERENCES employees (id),
    -- ★ 監査の時刻はアプリケーションの時計ではなく DB の時計で打つ
    exported_at   timestamptz NOT NULL DEFAULT now(),
    -- ★ この出力に使った 1 か月平均所定労働時間数（労基則 19 条 1 項 4 号）。
    --   年度の値は後から動くので、支払の根拠として当時の値を残す
    monthly_average_minutes int NOT NULL,

    CONSTRAINT payroll_exports_month_check
        CHECK (target_month = date_trunc('month', target_month)::date),
    CONSTRAINT payroll_exports_average_check
        CHECK (monthly_average_minutes > 0)
);

-- ★ 監査の照会は「この月を誰がいつ出したか」。対象月から引く
CREATE INDEX payroll_exports_month_idx
    ON payroll_exports (target_month, exported_at DESC);
```

| 列 | 決定 | 理由 |
| --- | --- | --- |
| `target_month` | `date`（月初日） | `monthly_settlements` と表現をそろえる。`char(7)` にすると月の比較と範囲検索が文字列比較になる |
| `exported_by` | `employees (id)` への外部キー | 誰が持ち出したかは監査の中心である。社員番号を写すと、番号の再割り当てで別人を指す |
| `exported_at` | `DEFAULT now()` | アプリケーションから渡さない。渡せる形にすると、監査の時刻を実行者が決められる |
| `monthly_average_minutes` | `> 0` の `CHECK` | 0 だと給与側がゼロ除算する。カレンダー未登録の年度は出力そのものを拒否する（ドメインモデル設計書 4）|
| 一意制約 | **置かない** | 同じ月を何度でも出せる（BR-18）。一意にすると再出力そのものができなくなる |

### 2.2 payroll_export_targets（対象社員と除外の理由）

```sql
CREATE TABLE payroll_export_targets (
    export_id   uuid    NOT NULL REFERENCES payroll_exports (id) ON DELETE CASCADE,
    employee_id uuid    NOT NULL REFERENCES employees (id),
    -- ★ NULL なら出力した社員。値が入っていれば除外した社員とその理由
    excluded_reason varchar(30),

    PRIMARY KEY (export_id, employee_id),
    CONSTRAINT payroll_export_targets_reason_check
        CHECK (excluded_reason IS NULL OR excluded_reason IN (
            'NOT_SUBMITTED', 'NOT_APPROVED', 'NOT_CLOSED',
            'NOT_SUBMITTABLE', 'NO_ATTENDANCE_RECORD', 'DUPLICATE_EMPLOYEE_NUMBER'))
);

-- ★ 除外だけを引く照会（人事が「誰が残っているか」を見る）
CREATE INDEX payroll_export_targets_excluded_idx
    ON payroll_export_targets (export_id)
    WHERE excluded_reason IS NOT NULL;
```

| 決定 | 理由 |
| --- | --- |
| **除外していない社員も行として残す** | 「誰を出したか」が記録の中身である。除外だけ残すと、対象社員の集合を後から在籍者の再計算で作ることになり、社員の登録が遡って直されると集合が変わる |
| `excluded_reason` を `NULL` 可の 1 列にする | 「出力した／除外した」を別の列で持つと、両方が立った行を作れる |
| `CHECK` で理由を列挙する | ドメインの `enum` に対応させる。新しい理由を足すときに DB も直す（落とし穴 50 と同じ考え）|
| `ON DELETE CASCADE` | 記録を消すのは記録ごとである。子だけが残ることは無い |
| 社員番号を写さない | 番号は再利用される。記録は ID で持ち、表示のときに `employee` から引く |

> **`payroll_export_targets` の行数が対象月の在籍者数に一致する、という制約は置かない。**
> 在籍者数は `employees` の時点解決で決まる値であり、
> `CHECK` からは参照できない（副問い合わせを書けない。落とし穴 8）。
> 一致はアプリケーション層の不変条件として持つ。

---

## 3. 主要なクエリ

### 3.1 対象月に在籍していた社員（`employee` が答える）

```sql
SELECT e.id, e.employee_number
  FROM employees e
 WHERE e.hired_on < :monthEndExclusive
   AND (e.retired_on IS NULL OR e.retired_on >= :monthStart)
 ORDER BY e.employee_number, e.hired_on
```

**在籍期間と対象月の重なりで絞る。**
`hired_on <= :monthStart` にすると月中入社が落ち、
`retired_on IS NULL` だけにすると月中退職の**最終給与が出ない**（落とし穴 63）。

`retired_on` は**最終在籍日**（閉区間）である。半開区間へそろえるのは
`infrastructure` の責務なので、ここでは `>= :monthStart` で比べる
（CLAUDE.md「期間の表現」）。

**この表を所有するのは `employee` である。**
クエリを `payroll` に置かず、`employee` に列挙の経路を新設する
（ドメインモデル設計書 6 の 1）。

社員番号 → 入社日の順に並べる。番号は再利用されうるので、
**番号だけでは順序が一意に決まらない**（同じ番号の 2 人が並ぶ月がある）。

### 3.2 出勤日数・欠勤日数（`attendance` が答える）

```sql
-- 出勤日数：実労働が 1 分でもある勤務日
SELECT count(*)
  FROM daily_attendances
 WHERE employee_id = :employeeId
   AND work_date >= :periodFrom
   AND work_date < :periodToExclusive
   AND working_minutes > 0
```

**`working_minutes > 0` で絞る。**
日次勤怠の行は打刻がそろった日にしか作られないが、
休憩だけの日（実労働 0 分）が理屈のうえでは作られうる。
行数をそのまま数えると、その日を出勤日に数えてしまう。

欠勤日数は「所定労働日 − 年休の日 − そのうち実労働がある日」で数える。
所定労働日は会社カレンダーが決めるので、**`attendance` がカレンダーと突き合わせる**
（`payroll` は数え方を持たない）。

### 3.3 その月に打刻があるか（`attendance` が答える）

```sql
SELECT EXISTS (
    SELECT 1 FROM time_clock_events
     WHERE employee_id = :employeeId
       AND work_date >= :periodFrom
       AND work_date < :periodToExclusive)
```

**月次勤怠の行の有無では判定しない。**
行は提出時に初めて作られるので、行が無いことは「下書き」を意味する
（[05 DB設計書](../05_申請承認と締め/DB設計書.md)）。
判定は**打刻という一次証拠**で行う（ドメインモデル設計書 3.2）。

### 3.4 出力の記録

```sql
INSERT INTO payroll_exports (id, target_month, exported_by, monthly_average_minutes)
VALUES (:id, :targetMonth, :exportedBy, :monthlyAverageMinutes)
```

`exported_at` は渡さない。DB の既定値に任せる。

---

## 4. 制約の一覧

| 制約名 | 種類 | 内容 |
| --- | --- | --- |
| `payroll_exports_pkey` | PK | `id` |
| `payroll_exports_exported_by_fkey` | FK | `employees (id)` |
| `payroll_exports_month_check` | CHECK | 対象月は月初日 |
| `payroll_exports_average_check` | CHECK | 月平均が正 |
| `payroll_exports_month_idx` | INDEX | 対象月から引く |
| `payroll_export_targets_pkey` | PK | `(export_id, employee_id)` |
| `payroll_export_targets_export_id_fkey` | FK | `payroll_exports (id)`（CASCADE）|
| `payroll_export_targets_employee_id_fkey` | FK | `employees (id)` |
| `payroll_export_targets_reason_check` | CHECK | 除外の理由は列挙のいずれか |
| `payroll_export_targets_excluded_idx` | INDEX | 除外だけを引く |

### 4.1 DB では防げないもの

| 内容 | どこで守るか |
| --- | --- |
| 締め済みの社員だけを出力すること | `application`。締め状態は `approval` が持つ |
| 対象社員の集合 = 対象月の在籍者 | `application`。`CHECK` に副問い合わせは書けない |
| 実行者が `HR` であること | `presentation` の認可 + `application` の検査 |
| 社員番号が対象月で一意であること | `application`。部分一意インデックスは在籍者しか守らない（ドメインモデル設計書 3.2）|

---

## 5. 順序の依存

`payroll_exports` は `employees` を参照する。
マイグレーションは **V8** とし、V2（社員）より後に置く。
`doc/_tools/build-migrations.py` の `PLAN` に登録済みである。

**適用済みのマイグレーションは書き換えない。**
新しい版で `ALTER TABLE` する（CLAUDE.md「適用済みマイグレーションの変更」）。

---

## 6. 制約の検証

**検証環境**: PostgreSQL 16 / 2026-09-06 実施。
V1〜V8 を空のデータベースへ順に適用し、`psql` から直接確かめた。

| ID | 観点 | 期待 | 結果 |
| --- | --- | --- | --- |
| IT-PAY-18 | 対象月に月初日以外を入れる | `payroll_exports_month_check` で拒否 | 確認 |
| IT-PAY-19 | 月平均を 0 にする | `payroll_exports_average_check` で拒否 | 確認 |
| IT-PAY-20 | 実在しない社員を実行者にする | `payroll_exports_exported_by_fkey` で拒否 | 確認 |
| IT-PAY-21 | **同じ月を 2 回記録する** | 通る。再出力は正当である | 確認 |
| IT-PAY-22 | `exported_at` を渡さずに挿入 | DB の `now()` が入る | 確認 |
| IT-PAY-23 | 実行者を退職させる | 記録は残る。参照は切れない | 確認 |
| IT-PAY-24 | 除外の理由に列挙外の値 | `payroll_export_targets_reason_check` で拒否 | 確認 |
| IT-PAY-25 | 同じ社員を同じ記録に 2 回 | `payroll_export_targets_pkey` で拒否 | 確認 |
| IT-PAY-26 | 記録を消すと対象社員も消える | `ON DELETE CASCADE` | 確認 |

**「拒否された」ではなく「狙った制約で拒否された」ことを確かめる**（落とし穴 17・25）。

---

## 7. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 出力した CSV のハッシュを記録に残すか（本文を保存せずに「あのとき渡した内容」を証明できる） | M3-a/C |
| 2 | 社員番号の再利用そのものを禁じるか（`employees_employee_number_uk` を全体一意にする）。いまは衝突した月に人事が割り当て直す | M3-b |
