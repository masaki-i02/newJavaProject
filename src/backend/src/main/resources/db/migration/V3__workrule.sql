-- 就業規則・カレンダー
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/02_就業規則・カレンダー/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

-- EXCLUDE 制約で uuid の等値比較と範囲型の重なり比較を
-- 1 つの GiST インデックスに同居させるために必要
CREATE TABLE work_rule_series (
    id          uuid         PRIMARY KEY,
    name        varchar(100) NOT NULL,
    abolished_on date,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT work_rule_series_name_key UNIQUE (name)
);

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

CREATE TRIGGER work_rule_series_set_updated_at BEFORE UPDATE ON work_rule_series
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER work_rules_set_updated_at BEFORE UPDATE ON work_rules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER work_rule_assignments_set_updated_at BEFORE UPDATE ON work_rule_assignments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER company_calendars_set_updated_at BEFORE UPDATE ON company_calendars
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
