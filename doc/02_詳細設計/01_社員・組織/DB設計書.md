# 社員・組織 DB設計書

| 項目 | 内容 |
| --- | --- |
| 文書番号 | KNT-DES-102 |
| 版 | 0.2 |
| 対象スキーマ | `employees` / `employee_credentials` / `employee_roles` / `departments` / `assignments` / `managerships` |
| 関連要件 | [BR-11 承認者の決定](../../01_要件定義/要件定義書.md#br-11-承認者の決定) |
| 関連文書 | [ドメインモデル設計書](ドメインモデル設計書.md) / [レビュー記録](../../06_レビュー記録/01_社員・組織_第1回.md) |
| 改訂 | 0.2（2026-09-01）設計レビューの指摘を反映 |

---

## 1. ER 図

![ER図](images/ER図.png)

<sub>図のソース: [`diagrams/ER図.mmd`](diagrams/ER図.mmd)</sub>

---

## 2. 設計の要点

| # | 設計 | 理由 |
| --- | --- | --- |
| 1 | 所属（`assignments`）と部署長（`managerships`）の**両方**を有効期間つきで保持する | BR-11 が基準日時点の所属部署とその長を要求する。片方だけでは過去月の承認者を誤る |
| 2 | 期間の重複を `EXCLUDE` 制約で禁止する | 兼務なし（BR-11 補足）を DB で保証する。アプリのバグや同時実行でも破られない |
| 3 | `EXCLUDE` の前提として `btree_gist` 拡張を有効にする | `uuid` の等値比較と範囲型の重なり比較を 1 つの GiST インデックスに同居させるため |
| 4 | 認証情報を `employee_credentials` に分離する | パスワードハッシュを社員マスタの参照系クエリに混ぜない。誤ってログや API に載る事故を構造で防ぐ |
| 5 | 認証 ID は社員番号とする（`employee_credentials` に ID 列を持たない） | 要件定義書 7 章。メールは退職者のアドレス再割り当てと衝突する |
| 6 | 部署階層は隣接リスト（`parent_id`）+ 再帰 CTE | 部署は数十件・3 階層。クロージャーテーブルは更新時の整合維持コストに見合わない |
| 7 | 部署の循環をトリガで拒否する | `CHECK` 制約では多段の循環を検出できない。並行更新時の限界は 3.4 に明記 |
| 8 | 他テーブルを参照する不変条件は**制約トリガ**で守る | `CHECK` は自分の行しか見られない。退職者を部署長にする等をアプリ任せにしない |
| 9 | 退職は行の削除ではなく `retired_on` で表現する | 過去の勤怠と承認履歴が社員を参照するため |
| 10 | 社員番号・メール・部署コードの一意性は**在籍中／現存のもの**に限る | 退職者のアドレスや廃止部署のコードを再利用できないと、運用年数に比例して使えない値が積み上がる |

### 2.1 雇用形態を持たない理由

要件定義書 3.1 が「全社員の所定労働時間は 1 日 8 時間」と定めており、
短時間勤務は対象外である。雇用形態の列を設けると `PART_TIME` を登録でき、
**所定 8 時間を前提とする BR-04 / BR-05 の計算が実態と合わなくなる。**
必要になった時点で、要件に短時間勤務者の所定を定めてから追加する。

### 2.2 時刻の生成元

| 種類 | 生成元 | 理由 |
| --- | --- | --- |
| `created_at` / `updated_at` | **DB の `now()`** | アプリの時刻を偽装しても記録の時刻は偽装できない。監査上この性質が重要 |
| 業務上の日時（承認日時、締め日時など） | **アプリの `Clock`** | テストで固定できる必要がある。アーキテクチャ設計書 6.3 / AR-09 |

**この 2 つを混同しない。** `created_at` は「行がいつ書かれたか」という技術的事実、
承認日時は「業務上いつ承認したか」という業務データである。

---

## 3. テーブル定義

### 3.0 前提となる拡張

```sql
-- EXCLUDE 制約で uuid の等値比較と範囲型の重なり比較を
-- 1 つの GiST インデックスに同居させるために必要
CREATE EXTENSION IF NOT EXISTS btree_gist;
```

これが無いと `assignments` / `managerships` の `EXCLUDE` 制約が
`data type uuid has no default operator class for access method "gist"` で作成に失敗する。
**Flyway の初回マイグレーションの先頭に置く。**

### 3.1 employees（社員）

| カラム | 型 | NULL | 説明 |
| --- | --- | --- | --- |
| `id` | `uuid` | × | 主キー |
| `employee_number` | `varchar(20)` | × | 社員番号。**認証 ID を兼ねる** |
| `name` | `varchar(100)` | × | 氏名 |
| `email` | `varchar(255)` | × | メールアドレス |
| `hired_on` | `date` | × | 入社日 |
| `retired_on` | `date` | ○ | 退職日。NULL は在籍中 |
| `version` | `bigint` | × | 楽観ロック |
| `created_at` / `updated_at` | `timestamptz` | × | 作成・更新日時 |

```sql
CREATE TABLE employees (
    id              uuid         PRIMARY KEY,
    employee_number varchar(20)  NOT NULL,
    name            varchar(100) NOT NULL,
    email           varchar(255) NOT NULL,
    hired_on        date         NOT NULL,
    retired_on      date,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),

    -- 退職日が入社日より前になることはありえない
    CONSTRAINT employees_employment_period_check
        CHECK (retired_on IS NULL OR retired_on >= hired_on)
);

-- 在籍中の社員の間でのみ一意とする。退職者の社員番号・メールは再利用できる
CREATE UNIQUE INDEX employees_employee_number_uk
    ON employees (employee_number) WHERE retired_on IS NULL;
-- 大文字小文字の違いで同一人物が二重登録されるのを防ぐ
CREATE UNIQUE INDEX employees_email_uk
    ON employees (lower(email)) WHERE retired_on IS NULL;
-- 退職者を含めた検索のためのインデックス（一意ではない）
CREATE INDEX employees_employee_number_idx ON employees (employee_number);
```

> **在籍中のみの一意制約にした代償**
> 「退職者と同じ社員番号でログインしようとしたとき、どちらの認証情報を使うか」が
> 曖昧になりうる。認証時は `retired_on IS NULL` で絞ることを M1-c の設計で明記する。

### 3.2 employee_credentials（認証情報）

```sql
CREATE TABLE employee_credentials (
    employee_id         uuid        PRIMARY KEY REFERENCES employees (id) ON DELETE CASCADE,
    password_hash       varchar(60) NOT NULL,
    password_changed_at timestamptz NOT NULL DEFAULT now(),

    -- 平文の誤保存を DB で拒否する。BCrypt の出力は $2a$ / $2b$ / $2y$ で始まる 60 文字
    CONSTRAINT employee_credentials_hash_format_check
        CHECK (password_hash ~ '^\$2[aby]\$' AND length(password_hash) = 60)
);
```

| 決定 | 理由 |
| --- | --- |
| ログイン ID の列を持たない | 認証 ID は社員番号（`employees.employee_number`）とする |
| `varchar(60)` | **BCrypt の出力は常に 60 文字。** 72 は入力パスワードのバイト上限であり別概念 |
| アカウントロックの列を持たない | 要件に無い（要件定義書 10 章 未決事項 #5）。閾値も解除手段も未定のまま列だけ作ると、ロックされた社員を復旧できなくなる |

`password_hash ~ '^\$2[aby]\$'` は **平文パスワードの誤保存を DB で拒否する** ための制約である。
アルゴリズムを変更する際は、この制約もあわせて変更する。

### 3.3 employee_roles（ロール）

```sql
CREATE TABLE employee_roles (
    employee_id uuid        NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    role        varchar(20) NOT NULL,
    granted_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (employee_id, role),
    CONSTRAINT employee_roles_role_check
        CHECK (role IN ('EMPLOYEE', 'APPROVER', 'HR', 'ADMIN'))
);
```

ロールは加算式のため、1 社員が複数行を持つ（要件定義書 4 章）。

> **`EMPLOYEE` 行の存在は DB では強制していない。**
> 「少なくとも 1 行が存在する」という制約は `CHECK` では書けない。
> アプリケーション側で、社員登録時に無条件で付与し、削除させないことで守る
> （[API設計書](API設計書.md) 3.3）。

> **`APPROVER` ロールと `managerships` の関係**
> 承認できるかどうかの実体は `managerships`（部署長かどうか）である。
> `APPROVER` ロールは **`managerships` から導出し、永続化しない。**
> 二重管理すると「部署長だがロールが無く 403 になる」「ロールはあるが承認対象が 0 件」
> という不整合が起きるため。詳細は [API設計書](API設計書.md) 2.1。

### 3.4 departments（部署）

```sql
CREATE TABLE departments (
    id           uuid         PRIMARY KEY,
    code         varchar(20)  NOT NULL,
    name         varchar(100) NOT NULL,
    parent_id    uuid         REFERENCES departments (id),
    abolished_on date,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    -- 自分自身を親にすることはできない（1 段の循環はここで防ぐ）
    CONSTRAINT departments_no_self_parent_check CHECK (parent_id IS NULL OR parent_id <> id)
);

-- 現存する部署の間でのみ一意とする。廃止した部署のコードは再利用できる
CREATE UNIQUE INDEX departments_code_uk ON departments (code) WHERE abolished_on IS NULL;
CREATE INDEX departments_parent_idx ON departments (parent_id);
```

#### 多段の循環の検出

`CHECK` 制約は自分の行しか参照できないため、`A → B → C → A` のような循環を検出できない。
トリガで祖先を遡って検査する。

```sql
CREATE OR REPLACE FUNCTION departments_reject_cycle() RETURNS trigger AS $$
DECLARE
    ancestor uuid := NEW.parent_id;
    depth    int  := 0;
BEGIN
    WHILE ancestor IS NOT NULL LOOP
        IF ancestor = NEW.id THEN
            RAISE EXCEPTION '部署の親子関係が循環しています (department_id=%)', NEW.id;
        END IF;
        depth := depth + 1;
        IF depth > 50 THEN
            RAISE EXCEPTION '部署階層が深すぎます (department_id=%)', NEW.id;
        END IF;
        SELECT parent_id INTO ancestor FROM departments WHERE id = ancestor;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER departments_reject_cycle_trigger
    BEFORE INSERT OR UPDATE OF parent_id ON departments
    FOR EACH ROW EXECUTE FUNCTION departments_reject_cycle();
```

> **どちらの制約が先に働くか**
> PostgreSQL では `BEFORE` トリガが `CHECK` 制約より先に評価される。
> したがって自己参照を設定した場合、実際に返るのは
> `departments_no_self_parent_check` ではなく **循環検出トリガの例外** である
> （検証で確認済み。7 章 IT-EMP-05）。
> `CHECK` 制約は、トリガが何らかの理由で無効化された場合の保険として残している。

深さの上限を設けているのは、万一データが既に循環していた場合に無限ループへ落ちないためである。
**この 50 は暴走防止であって業務制約ではない。**
要件定義書 2.2 は「本部 → 部 → 課の 3 階層」と定めているが、
組織改編で 4 階層になる可能性を残すため、階層数は DB では制限しない。

> **並行更新に対する限界**
> 行トリガは他トランザクションの未コミットの変更を見ない。
> 「A の親を C にする」と「C の親を A にする」が同時に走ると、
> **双方のトリガが循環を検出できず、コミット後に循環が成立する。**
> 部署の親変更は稀な操作なので、アプリケーション側で `departments` に対する
> 明示ロック（`LOCK TABLE departments IN SHARE ROW EXCLUSIVE MODE`）を取って
> 直列化する。この対策は [API設計書](API設計書.md) 3.6 に記載する。

### 3.5 assignments（所属）

```sql
CREATE TABLE assignments (
    id            uuid        PRIMARY KEY,
    employee_id   uuid        NOT NULL REFERENCES employees (id),
    department_id uuid        NOT NULL REFERENCES departments (id),
    valid_from    date        NOT NULL,
    valid_to      date,
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT assignments_period_check
        CHECK (valid_to IS NULL OR valid_to > valid_from),

    -- ★ 1 人の社員が、ある日付で複数の部署に所属することを物理的に禁止する（兼務なし）
    CONSTRAINT assignments_no_overlap
        EXCLUDE USING gist (
            employee_id WITH =,
            daterange(valid_from, valid_to, '[)') WITH &&
        )
);

CREATE INDEX assignments_department_idx ON assignments (department_id, valid_from DESC);
```

**この排他制約が、ドメイン側の `findEffective` が `Optional` を返せる根拠である。**

`(employee_id, valid_from)` の B-tree インデックスは置かない。
`assignments_no_overlap` が内部に作る GiST インデックスが `employee_id` の等値と
期間の両方を含むため、4.1 のクエリはそれで引ける。
1 社員あたりの所属行数は在籍年数分（数行）にすぎず、重複したインデックスは
書き込みコストだけを増やす。

`version` と `updated_at` を持つのは、**異動の登録が既存行の `valid_to` を UPDATE する**
ためである（[API設計書](API設計書.md) 3.4）。誰がいつ所属期間を書き換えたかを追えないと、
承認者が変わった原因を調査できない。

### 3.6 managerships（部署長）

```sql
CREATE TABLE managerships (
    id            uuid        PRIMARY KEY,
    department_id uuid        NOT NULL REFERENCES departments (id),
    employee_id   uuid        NOT NULL REFERENCES employees (id),
    valid_from    date        NOT NULL,
    valid_to      date,
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT managerships_period_check
        CHECK (valid_to IS NULL OR valid_to > valid_from),

    -- ★ 1 つの部署に、ある日付で複数の長がいることを禁止する
    CONSTRAINT managerships_no_overlap
        EXCLUDE USING gist (
            department_id WITH =,
            daterange(valid_from, valid_to, '[)') WITH &&
        )
);

-- 「この社員が長を務める部署」を引く。承認者の閲覧範囲の判定に使う（4.4）
CREATE INDEX managerships_employee_idx ON managerships (employee_id, valid_from DESC);
```

**部署長の兼任は許容する。** 排他制約は部署単位なので、
1 人が複数部署の長を兼ねることは妨げない。本部長が部の長を兼ねる運用があるため
（要件定義書 BR-11 補足）。

> この兼任が、BR-11 の「自己承認の禁止」と組み合わさると
> **本部長本人の承認者が根まで遡っても得られない** 状況を生む。
> BR-11 の 5（人事へのエスカレーション）はこのために存在する。

### 3.7 他テーブルを参照する不変条件（制約トリガ）

`CHECK` 制約は自分の行しか参照できない。次の 3 つは他テーブルの状態に依存するため、
制約トリガで検証する。

```sql
-- 所属: 入社日以降であること、廃止済みの部署でないこと
CREATE OR REPLACE FUNCTION assignments_validate_references() RETURNS trigger AS $$
DECLARE
    emp_hired_on   date;
    dept_abolished date;
BEGIN
    SELECT hired_on INTO emp_hired_on FROM employees WHERE id = NEW.employee_id;
    IF NEW.valid_from < emp_hired_on THEN
        RAISE EXCEPTION '入社日より前の所属は登録できません (valid_from=%, hired_on=%)',
            NEW.valid_from, emp_hired_on;
    END IF;

    SELECT abolished_on INTO dept_abolished FROM departments WHERE id = NEW.department_id;
    IF dept_abolished IS NOT NULL AND NEW.valid_from >= dept_abolished THEN
        RAISE EXCEPTION '廃止済みの部署へは配属できません (department_id=%)', NEW.department_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER assignments_validate_references_trigger
    AFTER INSERT OR UPDATE ON assignments
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION assignments_validate_references();

-- 部署長: 就任時点で在籍していること、廃止済みの部署でないこと
CREATE OR REPLACE FUNCTION managerships_validate_references() RETURNS trigger AS $$
DECLARE
    emp_hired_on   date;
    emp_retired_on date;
    dept_abolished date;
BEGIN
    SELECT hired_on, retired_on INTO emp_hired_on, emp_retired_on
      FROM employees WHERE id = NEW.employee_id;
    IF NEW.valid_from < emp_hired_on THEN
        RAISE EXCEPTION '入社日より前に部署長へ就任することはできません (valid_from=%)', NEW.valid_from;
    END IF;
    IF emp_retired_on IS NOT NULL AND NEW.valid_from > emp_retired_on THEN
        RAISE EXCEPTION '退職済みの社員を部署長に設定できません (employee_id=%)', NEW.employee_id;
    END IF;

    SELECT abolished_on INTO dept_abolished FROM departments WHERE id = NEW.department_id;
    IF dept_abolished IS NOT NULL AND NEW.valid_from >= dept_abolished THEN
        RAISE EXCEPTION '廃止済みの部署に部署長を設定できません (department_id=%)', NEW.department_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER managerships_validate_references_trigger
    AFTER INSERT OR UPDATE ON managerships
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION managerships_validate_references();
```

> **DB で防げないもの（明記しておく）**
> - 社員を退職させた後に、その社員が長を務める `managerships` が開いたまま残ること
>   → 退職 API の副作用として閉じる（[API設計書](API設計書.md) 3.5）
> - 部署を廃止した後に、その部署への所属が開いたまま残ること
>   → 部署廃止 API の副作用として閉じる
>
> これらは「既存行が後から不正になる」種類の問題であり、
> 行トリガでは検出できない。**アプリケーションの責務であることを明示する。**

### 3.8 updated_at の自動更新

`DEFAULT now()` は INSERT 時にしか効かない。UPDATE でも更新されるようトリガを置く。

```sql
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER employees_set_updated_at BEFORE UPDATE ON employees
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER departments_set_updated_at BEFORE UPDATE ON departments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER assignments_set_updated_at BEFORE UPDATE ON assignments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER managerships_set_updated_at BEFORE UPDATE ON managerships
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

---

## 4. 主要なクエリ

### 4.1 指定日の所属部署

```sql
SELECT d.*
FROM assignments a
JOIN departments d ON d.id = a.department_id
WHERE a.employee_id = :employeeId
  AND a.valid_from <= :date
  AND (a.valid_to IS NULL OR a.valid_to > :date);
```

排他制約の GiST インデックスが効く。結果は高々 1 行。

### 4.2 部署の祖先を辿る（承認者の導出）

```sql
WITH RECURSIVE ancestors AS (
    SELECT id, parent_id, code, name, abolished_on, 0 AS depth
    FROM departments
    WHERE id = :departmentId

    UNION ALL

    SELECT d.id, d.parent_id, d.code, d.name, d.abolished_on, a.depth + 1
    FROM departments d
    JOIN ancestors a ON d.id = a.parent_id
)
SELECT a.id, a.name, a.abolished_on, m.employee_id AS manager_id
FROM ancestors a
LEFT JOIN managerships m
       ON m.department_id = a.id
      AND m.valid_from <= :date
      AND (m.valid_to IS NULL OR m.valid_to > :date)
ORDER BY a.depth;
```

自分自身から根まで、近い順に部署と部署長を返す。

> **このクエリは「事実」だけを返す。**
> 自己承認の禁止、退職者のスキップ、人事へのエスカレーション（BR-11 の 4 と 5）は
> `approval` コンテキストの `ApproverPolicy` が適用する。
> SQL に業務ルールを埋め込まない（[アーキテクチャ設計書](../00_共通/アーキテクチャ設計書.md) 6.4）。

### 4.3 部署配下の社員

```sql
WITH RECURSIVE descendants AS (
    SELECT id FROM departments WHERE id = :departmentId
    UNION ALL
    SELECT d.id FROM departments d JOIN descendants x ON d.parent_id = x.id
)
SELECT DISTINCT e.*
FROM employees e
JOIN assignments a ON a.employee_id = e.id
WHERE a.department_id IN (SELECT id FROM descendants)
  AND a.valid_from <= :date
  AND (a.valid_to IS NULL OR a.valid_to > :date)
  AND (:includeRetired OR e.retired_on IS NULL OR e.retired_on >= :date);
```

`includeRetired` の既定は `false`（[API設計書](API設計書.md) 3.2）。
`retired_on >= :date` を含めるのは、**基準日時点では在籍していた退職者**を
既定でも含めるためである。含めないと、退職月の勤怠を承認者が確認できなくなる。

### 4.4 指定社員が指定日に長を務める部署（承認者の閲覧範囲）

```sql
SELECT m.department_id
FROM managerships m
WHERE m.employee_id = :employeeId
  AND m.valid_from <= :date
  AND (m.valid_to IS NULL OR m.valid_to > :date);
```

`managerships_employee_idx` が効く。
結果の各部署に対して 4.3 を適用したものが、その承認者の閲覧範囲になる。

### 4.5 全社員の一覧（人事・管理者）

```sql
SELECT e.*, d.id AS department_id, d.code, d.name
FROM employees e
LEFT JOIN assignments a
       ON a.employee_id = e.id
      AND a.valid_from <= :date
      AND (a.valid_to IS NULL OR a.valid_to > :date)
LEFT JOIN departments d ON d.id = a.department_id
WHERE (:includeRetired OR e.retired_on IS NULL)
ORDER BY e.employee_number;
```

**所属を `LEFT JOIN` にする。** 内部結合にすると、未来日入社の社員や
所属が閉じられた退職者が一覧から消え、登録直後の確認ができなくなる。

---

## 5. 制約の一覧

| 制約名 | 種類 | 守るもの |
| --- | --- | --- |
| `employees_employment_period_check` | CHECK | 退職日が入社日以降 |
| `employees_employee_number_uk` | 部分 UNIQUE | 在籍中の社員番号の一意性 |
| `employees_email_uk` | 部分 UNIQUE | 在籍中のメールアドレスの一意性（大文字小文字を無視） |
| `employee_credentials_hash_format_check` | CHECK | **平文パスワードの保存を拒否** |
| `employee_roles_role_check` | CHECK | ロールが定義された 4 種のいずれか |
| `departments_no_self_parent_check` | CHECK | 自分自身を親にしない |
| `departments_code_uk` | 部分 UNIQUE | 現存する部署コードの一意性 |
| `departments_reject_cycle_trigger` | TRIGGER | **多段の循環を拒否**（並行更新時の限界は 3.4） |
| `assignments_no_overlap` | EXCLUDE | **1 社員の所属期間が重複しない（兼務なし）** |
| `managerships_no_overlap` | EXCLUDE | **1 部署の部署長期間が重複しない** |
| `assignments_validate_references_trigger` | 制約 TRIGGER | 入社日以降・廃止部署でないこと |
| `managerships_validate_references_trigger` | 制約 TRIGGER | **退職者を部署長にしない**・廃止部署でないこと |
| `*_set_updated_at` | TRIGGER | `updated_at` の自動更新 |

### 5.1 DB では防げないもの

| 内容 | 守る場所 |
| --- | --- |
| `employee_roles` に `EMPLOYEE` が必ず 1 行あること | アプリケーション（社員登録時に無条件付与） |
| 退職時に `assignments` / `managerships` を閉じること | アプリケーション（退職 API の副作用） |
| 部署廃止時に配下の所属を閉じること | アプリケーション（部署廃止 API の副作用） |
| 部署の親変更が並行実行されたときの循環 | アプリケーション（明示ロックによる直列化） |

**黙って抜けているのが最も危険なので、DB で守れない範囲を明示する。**

---

## 6. インデックス設計

| インデックス | 対象 | 用途 |
| --- | --- | --- |
| `assignments_no_overlap`（GiST・EXCLUDE が生成） | `(employee_id, daterange)` | 指定日の所属を引く（4.1） |
| `assignments_department_idx` | `(department_id, valid_from DESC)` | 部署の在籍者を引く（4.3） |
| `managerships_no_overlap`（GiST・EXCLUDE が生成） | `(department_id, daterange)` | 部署長を引く（4.2） |
| `managerships_employee_idx` | `(employee_id, valid_from DESC)` | **長を務める部署を引く（4.4）** |
| `departments_parent_idx` | `(parent_id)` | 再帰 CTE で子を辿る |
| `employees_employee_number_idx` | `(employee_number)` | 退職者を含む社員番号の検索 |

**期間の絞り込みは `EXCLUDE` が生成する GiST インデックスが担う。**
同じ列に B-tree を重ねて置かない。書き込みコストだけが増えるため。

---

## 7. 制約の検証

**検証環境**: PostgreSQL 16 / `btree_gist` 有効 / 2026-09-01 実施

| ID | 検証内容 | 期待 | 結果 |
| --- | --- | --- | --- |
| IT-EMP-01 | 同一社員に期間の重なる所属を登録 | `assignments_no_overlap` で拒否 | 済 |
| IT-EMP-02 | 同一部署に期間の重なる部署長を登録 | `managerships_no_overlap` で拒否 | 済 |
| IT-EMP-03 | 平文のパスワードを保存 | `employee_credentials_hash_format_check` で拒否 | 済 |
| IT-EMP-04 | 部署の親子に多段の循環を作る | `departments_reject_cycle_trigger` で拒否 | 済 |
| IT-EMP-05 | 部署の親に自分自身を設定 | **循環検出トリガ**で拒否（下記の注記を参照） | 済 |
| IT-EMP-06 | 退職日を入社日より前に設定 | `employees_employment_period_check` で拒否 | 済 |
| IT-EMP-07 | 在籍中の社員と大文字違いの同一メールを登録 | `employees_email_uk` で拒否 | 済 |
| IT-EMP-08 | 退職者と同じメールで新規登録 | **成功する**（再利用を認める） | 済 |
| IT-EMP-09 | 入社日より前の所属を登録 | `assignments_validate_references_trigger` で拒否 | 済 |
| IT-EMP-10 | 退職済みの社員を部署長に設定 | `managerships_validate_references_trigger` で拒否 | 済 |
| IT-EMP-11 | 廃止済みの部署へ配属 | `assignments_validate_references_trigger` で拒否 | 済 |
| IT-EMP-12 | 期間を区切って異動・部署長交代を登録 | 成功する | 済 |
| IT-EMP-13 | **同一部署を起点に、対象日だけを変えて 4.2 を実行** | 対象日により異なる部署長が返る | 済 |
| IT-EMP-14 | `updated_at` が UPDATE で更新される | 更新後に値が変わる | 済 |
| IT-EMP-15 | **社員を保存して読み戻す** | 同じ値になる。メールは小文字に正規化されている | 済 |
| IT-EMP-16 | **社員番号で引く**（認証 ID を兼ねる） | 引ける。存在しない番号では空 | 済 |
| IT-EMP-17 | **退職日当日・翌日の在籍者一覧** | 当日は含む、翌日は含まない。退職者を含める指定なら引ける | 済 |
| IT-EMP-18 | **複数ロールの保存** | すべて読み戻せる | 済 |
| IT-EMP-19 | **祖先の列挙（再帰 CTE）** | 自分自身から根へ向かう順で返る | 済 |
| IT-EMP-20 | **配下の列挙（再帰 CTE）** | 自分自身から順に返る | 済 |
| IT-EMP-21 | **無期限の所属の往復** | DB では `NULL`、ドメインでは番兵。遠い未来でも有効 | 済 |
| IT-EMP-22 | **異動日当日の所属** | 半開区間なので新しい部署を返す | 済 |
| IT-EMP-23 | **部署長の交代** | 基準日時点の長を返す | 済 |
| IT-EMP-24 | **部署長の兼任** | 1 人が複数部署の長を務められる | 済 |
| IT-EMP-25 | **組織図の導出（実データ）** | 所属・祖先・部署長・在籍が一貫して引ける | 済 |
| IT-EMP-26 | **月中入社の基準日**（BR-11 の 1） | 所属開始日を返す。翌月は空 | 済 |

### IT-EMP-13 の確認結果

**入力のうち `:date` だけを変え、`:departmentId` は「第一営業課」に固定して実行した。**

| `:date` | depth 0 第一営業課 | depth 1 第一営業部 | depth 2 営業本部 |
| --- | --- | --- | --- |
| 2025-06-01 | （長なし） | 佐藤花子 | 田中一郎 |
| 2026-06-01 | （長なし） | **田中一郎** | 田中一郎 |

第一営業部の長が 2026-04-01 に佐藤花子から田中一郎へ交代したことが、
同一のクエリで対象日を変えるだけで反映されている。
**所属と部署長の双方を履歴化した設計（設計の要点 1）が意図どおり機能している。**

なお 2026-06-01 の配置では、田中一郎本人の承認者が根まで遡っても得られない。
これは BR-11 の 5（人事へのエスカレーション）が必要な理由そのものであり、
`approval` コンテキストの設計で扱う。

---

## 8. 未決事項

| # | 内容 | 判断の時期 |
| --- | --- | --- |
| 1 | 認証時に退職者を除外する具体的な実装 | M1-c |
| 2 | 異動・退職の遡及訂正をどこまで許すか | M1-a |
| 3 | 部署廃止時に配下の所属をどう閉じるか（自動か、エラーにして手動対応か） | M1-a |
