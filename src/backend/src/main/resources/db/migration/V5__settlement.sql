-- 勤怠（月次清算）
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/04_勤怠_月次清算/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

CREATE TABLE monthly_settlements (
    id                              uuid        PRIMARY KEY,
    employee_id                     uuid        NOT NULL REFERENCES employees (id),
    target_month                    date        NOT NULL,
    period_from                     date        NOT NULL,
    period_to_exclusive             date        NOT NULL,
    work_rule_series_id             uuid        NOT NULL REFERENCES work_rule_series (id),
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

    annual_agreement_subject_before_minutes int NOT NULL DEFAULT 0,
    monthly_agreement_limit_minutes int         NOT NULL DEFAULT 2700,
    annual_agreement_limit_minutes  int         NOT NULL DEFAULT 21600,
    exceeds_monthly_agreement_limit boolean     NOT NULL,
    exceeds_annual_agreement_limit  boolean     NOT NULL,

    calculated_at                   timestamptz NOT NULL,
    version                         bigint      NOT NULL DEFAULT 0,
    created_at                      timestamptz NOT NULL DEFAULT now(),
    updated_at                      timestamptz NOT NULL DEFAULT now(),

    -- 対象月は必ず月初日で表現する（月の表現ゆらぎを DB で封じる）
    CONSTRAINT monthly_settlements_month_check
        CHECK (target_month = date_trunc('month', target_month)::date),

    -- ★ 清算期間は対象月の内側に収まる半開区間（暦月 ∩ 在籍期間）
    CONSTRAINT monthly_settlements_period_check CHECK (
        period_from >= target_month
        AND period_to_exclusive > period_from
        AND period_to_exclusive <= (target_month + INTERVAL '1 month')::date
    ),

    -- ★ 総枠 = 清算期間の暦日数 ÷ 7 × 週法定労働時間（分未満は切り捨て）。
    --   週法定労働時間は 40 時間で固定なので式を制約にできる
    CONSTRAINT monthly_settlements_statutory_limit_check
        CHECK (statutory_total_limit_minutes
               = (period_to_exclusive - period_from) * 2400 / 7),
    CONSTRAINT monthly_settlements_system_check
        CHECK (working_time_system IN ('FIXED', 'FLEX')),

    CONSTRAINT monthly_settlements_non_negative_check
        CHECK (working_minutes >= 0 AND legal_holiday_minutes >= 0
               AND target_working_minutes >= 0 AND scheduled_total_minutes >= 0
               AND statutory_total_limit_minutes > 0
               AND daily_overtime_minutes >= 0 AND weekly_overtime_minutes >= 0
               AND overtime_minutes >= 0 AND shortage_minutes >= 0
               AND night_minutes >= 0 AND core_time_absence_minutes >= 0
               AND annual_agreement_subject_before_minutes >= 0
               AND monthly_agreement_limit_minutes > 0
               AND annual_agreement_limit_minutes > 0),

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

    -- ★ 時間外と不足が同時に正になるのは、所定総 > 法定総枠 の月に限る
    CONSTRAINT monthly_settlements_overtime_shortage_check
        CHECK (overtime_minutes = 0 OR shortage_minutes = 0
               OR scheduled_total_minutes > statutory_total_limit_minutes),

    -- ★ 36 協定の判定は他の列から一意に決まる（BR-12）
    CONSTRAINT monthly_settlements_monthly_agreement_check
        CHECK (exceeds_monthly_agreement_limit
               = (overtime_minutes + legal_holiday_minutes
                  > monthly_agreement_limit_minutes)),
    CONSTRAINT monthly_settlements_annual_agreement_check
        CHECK (exceeds_annual_agreement_limit
               = (annual_agreement_subject_before_minutes
                  + overtime_minutes + legal_holiday_minutes
                  > annual_agreement_limit_minutes)),

    CONSTRAINT monthly_settlements_employee_month_uk UNIQUE (employee_id, target_month)
);

CREATE INDEX monthly_settlements_agreement_idx
    ON monthly_settlements (target_month)
    WHERE exceeds_monthly_agreement_limit OR exceeds_annual_agreement_limit;

CREATE TABLE weekly_overtimes (
    id                      uuid PRIMARY KEY,
    monthly_settlement_id   uuid NOT NULL
                            REFERENCES monthly_settlements (id) ON DELETE CASCADE,
    week_start              date NOT NULL,
    week_end_exclusive      date NOT NULL,
    statutory_inside_minutes int NOT NULL,
    overtime_minutes         int NOT NULL,

    -- 週は必ず 7 日間。上限は半開区間なので +7
    CONSTRAINT weekly_overtimes_span_check
        CHECK (week_end_exclusive = week_start + 7),
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

CREATE TRIGGER monthly_settlements_set_updated_at BEFORE UPDATE ON monthly_settlements
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
