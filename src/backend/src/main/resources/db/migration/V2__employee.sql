-- 社員・組織
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/01_社員・組織/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

-- EXCLUDE 制約で uuid の等値比較と範囲型の重なり比較を
-- 1 つの GiST インデックスに同居させるために必要
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

CREATE TABLE employee_credentials (
    employee_id         uuid        PRIMARY KEY REFERENCES employees (id) ON DELETE CASCADE,
    password_hash       varchar(60) NOT NULL,
    password_changed_at timestamptz NOT NULL DEFAULT now(),

    -- 平文の誤保存を DB で拒否する。BCrypt の出力は $2a$ / $2b$ / $2y$ で始まる 60 文字
    CONSTRAINT employee_credentials_hash_format_check
        CHECK (password_hash ~ '^\$2[aby]\$' AND length(password_hash) = 60)
);

CREATE TABLE employee_roles (
    employee_id uuid        NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    role        varchar(20) NOT NULL,
    granted_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (employee_id, role),
    CONSTRAINT employee_roles_role_check
        CHECK (role IN ('EMPLOYEE', 'APPROVER', 'HR', 'ADMIN'))
);

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

CREATE TRIGGER employees_set_updated_at BEFORE UPDATE ON employees
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER departments_set_updated_at BEFORE UPDATE ON departments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER assignments_set_updated_at BEFORE UPDATE ON assignments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER managerships_set_updated_at BEFORE UPDATE ON managerships
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
