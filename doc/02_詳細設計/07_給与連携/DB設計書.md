# 給与連携 DB設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-702 |
| 版 | 0.1 |
| 対象スキーマ | `payroll_exports` |
| 関連要件 | BR-18 |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [API設計書](API設計書.md) / [設計規約チェックリスト](../00_共通/設計規約チェックリスト.md) |

---

## 1. 設計の要点

| # | 設計 | 理由 |
| --- | --- | --- |
| 1 | **表は 1 つだけ。出力の記録しか持たない** | 出力する値はすべて他の表にある。写すと、締め済みの値が動かないという前提が 2 か所に分かれる |
| 2 | **CSV の本文を保存しない** | 締め済みの値は動かないので、いつでも同じ内容を作り直せる。保存すると全社員の賃金データの複製が増える |
| 3 | **「出力済み」を月次勤怠に書き戻さない** | 締めと二重に管理することになる。記録は監査のためであり、再出力の可否を左右しない |
| 4 | 除外した社員の件数だけを残し、社員は残さない | 誰が未締めだったかは月次勤怠の側にある。写すと締め直した後に食い違う |
| 5 | `exported_at` は **DB の `now()`** | 監査の時刻は偽装できてはならない（CLAUDE.md「時刻の生成元」）|
| 6 | 版（楽観ロック）を持たない | 追記専用であり、更新も削除もしない |

---

## 2. テーブル定義

### 2.1 payroll_exports（出力の記録）

```sql
CREATE TABLE payroll_exports (
    id            uuid        PRIMARY KEY,
    -- ★ 対象月。月初日で持つ（monthly_settlements.target_month と同じ表現）
    target_month  date        NOT NULL,
    -- ★ 実行した人事担当。退職しても記録は消さないので ON DELETE は付けない
    exported_by   uuid        NOT NULL REFERENCES employees (id),
    -- ★ 監査の時刻はアプリケーションの時計ではなく DB の時計で打つ
    exported_at   timestamptz NOT NULL DEFAULT now(),
    -- ★ 出力した行数と、締まっていないなどの理由で除外した社員数
    row_count     int         NOT NULL,
    excluded_count int        NOT NULL,

    CONSTRAINT payroll_exports_month_check
        CHECK (target_month = date_trunc('month', target_month)::date),
    CONSTRAINT payroll_exports_count_check
        CHECK (row_count >= 0 AND excluded_count >= 0)
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
| 一意制約 | **置かない** | 同じ月を何度でも出せる（BR-18）。一意にすると再出力そのものができなくなる |

> **`row_count + excluded_count` が対象月の在籍者数に一致する、という制約は置かない。**
> 在籍者数は `employees` と `employee_assignments` の時点解決で決まる値であり、
> `CHECK` からは参照できない（副問い合わせを書けない。落とし穴 8）。
> 一致はアプリケーション層の不変条件として持つ。

---

## 3. 主要なクエリ

### 3.1 対象月に在籍していた社員

```sql
SELECT e.id, e.employee_number
  FROM employees e
 WHERE e.hired_on < :monthEndExclusive
   AND (e.retired_on IS NULL OR e.retired_on >= :monthStart)
 ORDER BY e.employee_number
```

**在籍期間と対象月の重なりで絞る。**
`hired_on <= :monthStart` にすると月中入社が落ち、
`retired_on IS NULL` だけにすると月中退職の**最終給与が出ない**（落とし穴 63）。

`retired_on` は**最終在籍日**（閉区間）である。半開区間へそろえるのは
`infrastructure` の責務なので、ここでは `>= :monthStart` で比べる
（CLAUDE.md「期間の表現」）。

社員番号順に並べる。**順序が安定していないと、同じ月の CSV の差分が取れない。**

### 3.2 出勤日数（`attendance` が答える）

```sql
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

### 3.3 出力の記録

```sql
INSERT INTO payroll_exports (id, target_month, exported_by, row_count, excluded_count)
VALUES (:id, :targetMonth, :exportedBy, :rowCount, :excludedCount)
```

`exported_at` は渡さない。DB の既定値に任せる。

---

## 4. 制約の一覧

| 制約名 | 種類 | 内容 |
| --- | --- | --- |
| `payroll_exports_pkey` | PK | `id` |
| `payroll_exports_exported_by_fkey` | FK | `employees (id)` |
| `payroll_exports_month_check` | CHECK | 対象月は月初日 |
| `payroll_exports_count_check` | CHECK | 行数・除外数が負でない |
| `payroll_exports_month_idx` | INDEX | 対象月から引く |

### 4.1 DB では防げないもの

| 内容 | どこで守るか |
| --- | --- |
| 締め済みの社員だけを出力すること | `application`。締め状態は `approval` が持つ |
| `row_count + excluded_count` = 在籍者数 | `application`。`CHECK` に副問い合わせは書けない |
| 実行者が `HR` であること | `presentation` の認可 + `application` の検査 |

---

## 5. 順序の依存

`payroll_exports` は `employees` を参照する。
マイグレーションは **V8** とし、V1（社員）より後に置く。

**適用済みのマイグレーションは書き換えない。**
新しい版で `ALTER TABLE` する（CLAUDE.md「適用済みマイグレーションの変更」）。

---

## 6. 制約の検証

| ID | 観点 | 期待 |
| --- | --- | --- |
| IT-PAY-16 | 対象月に月初日以外を入れる | `payroll_exports_month_check` で拒否 |
| IT-PAY-17 | 行数を負にする | `payroll_exports_count_check` で拒否 |
| IT-PAY-18 | 除外数を負にする | `payroll_exports_count_check` で拒否 |
| IT-PAY-19 | 実在しない社員を実行者にする | 外部キーで拒否 |
| IT-PAY-20 | **同じ月を 2 回記録する** | 通る。再出力は正当である |
| IT-PAY-21 | `exported_at` を渡さずに挿入 | DB の `now()` が入る |
| IT-PAY-22 | 実行者を退職させる | 記録は残る。参照は切れない |

**「拒否された」ではなく「狙った制約で拒否された」ことを確かめる**（落とし穴 17・25）。
