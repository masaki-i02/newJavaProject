-- 勤怠（打刻・日次集計）
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/03_勤怠_打刻と日次集計/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

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
    -- 要件に無い打刻手段を増やさない。訂正申請の承認による追記だけを別扱いする
    CONSTRAINT time_clock_events_source_check
        CHECK (source IN ('WEB', 'CORRECTION')),

    -- 取消行は必ず対象を持ち、通常の打刻は持たない。
    -- 訂正で追記された打刻（source = 'CORRECTION'）にも理由を必須とする
    CONSTRAINT time_clock_events_revocation_check CHECK (
        (entry_type = 'REVOCATION' AND revokes_event_id IS NOT NULL AND reason IS NOT NULL)
        OR
        (entry_type = 'ENTRY' AND revokes_event_id IS NULL
             AND (source <> 'CORRECTION' OR reason IS NOT NULL))
    ),

    -- パーティションキーを主キーに含めるのは PostgreSQL の制約
    PRIMARY KEY (work_date, id),

    -- 取消対象を (work_date, employee_id, id) で参照するための一意制約
    CONSTRAINT time_clock_events_owner_uk UNIQUE (work_date, employee_id, id),

    -- ★ 取消対象は「同じ勤務日の、同じ社員の」打刻でなければならない。
    --   employee_id を参照に含めないと、他人の打刻を取り消せてしまう
    CONSTRAINT time_clock_events_revokes_fk
        FOREIGN KEY (work_date, employee_id, revokes_event_id)
        REFERENCES time_clock_events (work_date, employee_id, id)
) PARTITION BY RANGE (work_date);

-- 同じ打刻を二重に取り消せない
CREATE UNIQUE INDEX time_clock_events_revokes_uk
    ON time_clock_events (work_date, revokes_event_id)
    WHERE revokes_event_id IS NOT NULL;

CREATE INDEX time_clock_events_employee_date_idx
    ON time_clock_events (employee_id, work_date, occurred_at);

-- 要件 7 章のデータ保持期間（5 年）ぶんを事前に定義する
CREATE TABLE time_clock_events_2026 PARTITION OF time_clock_events
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE time_clock_events_2027 PARTITION OF time_clock_events
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE time_clock_events_2028 PARTITION OF time_clock_events
    FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
CREATE TABLE time_clock_events_2029 PARTITION OF time_clock_events
    FOR VALUES FROM ('2029-01-01') TO ('2030-01-01');
CREATE TABLE time_clock_events_2030 PARTITION OF time_clock_events
    FOR VALUES FROM ('2030-01-01') TO ('2031-01-01');
-- 想定外の日付の受け皿。ここに行が入ること自体が異常なので監視する
CREATE TABLE time_clock_events_default PARTITION OF time_clock_events DEFAULT;

CREATE OR REPLACE FUNCTION time_clock_events_validate() RETURNS trigger AS $$
DECLARE
    target_entry_type varchar(20);
    target_event_type varchar(20);
    punched_on        date;
BEGIN
    -- ① 打刻時刻は勤務日の当日か翌日でなければならない（BR-03 の日跨ぎを許容する幅）
    punched_on := (NEW.occurred_at AT TIME ZONE 'Asia/Tokyo')::date;
    IF punched_on < NEW.work_date OR punched_on > NEW.work_date + 1 THEN
        RAISE EXCEPTION '打刻時刻が勤務日から離れすぎています (work_date=%, occurred_at=%)',
            NEW.work_date, NEW.occurred_at;
    END IF;

    IF NEW.entry_type = 'REVOCATION' THEN
        SELECT entry_type, event_type INTO target_entry_type, target_event_type
          FROM time_clock_events
         WHERE work_date = NEW.work_date AND id = NEW.revokes_event_id;

        -- ② 取消の対象は ENTRY に限る。取消の取消は認めない
        IF target_entry_type <> 'ENTRY' THEN
            RAISE EXCEPTION '取消できるのは通常の打刻だけです (revokes_event_id=%)',
                NEW.revokes_event_id;
        END IF;

        -- ③ 取消行の打刻種別は対象と一致していなければならない
        IF target_event_type <> NEW.event_type THEN
            RAISE EXCEPTION '取消行の打刻種別が対象と一致しません (% <> %)',
                NEW.event_type, target_event_type;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER time_clock_events_validate_trigger
    AFTER INSERT ON time_clock_events
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION time_clock_events_validate();

CREATE TABLE daily_attendances (
    id                                uuid        PRIMARY KEY,
    employee_id                       uuid        NOT NULL REFERENCES employees (id),
    work_date                         date        NOT NULL,
    day_type                          varchar(20) NOT NULL,
    working_time_system               varchar(20) NOT NULL,
    work_rule_id                      uuid        NOT NULL REFERENCES work_rules (id),
    working_minutes                   int         NOT NULL,
    break_minutes                     int         NOT NULL,
    base_minutes                      int         NOT NULL,
    overtime_within_statutory_minutes int         NOT NULL,
    overtime_beyond_statutory_minutes int         NOT NULL,
    night_minutes                     int         NOT NULL,
    legal_holiday_minutes             int         NOT NULL,
    break_requirement_satisfied       boolean     NOT NULL,
    calculated_at                     timestamptz NOT NULL DEFAULT now(),
    version                           bigint      NOT NULL DEFAULT 0,
    created_at                        timestamptz NOT NULL DEFAULT now(),
    updated_at                        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT daily_attendances_day_type_check
        CHECK (day_type IN ('WORKDAY', 'LEGAL_HOLIDAY', 'NON_LEGAL_HOLIDAY')),
    CONSTRAINT daily_attendances_system_check
        CHECK (working_time_system IN ('FIXED', 'FLEX')),

    CONSTRAINT daily_attendances_non_negative_check
        CHECK (working_minutes >= 0 AND break_minutes >= 0
               AND base_minutes >= 0
               AND overtime_within_statutory_minutes >= 0
               AND overtime_beyond_statutory_minutes >= 0
               AND night_minutes >= 0
               AND legal_holiday_minutes >= 0),

    -- ★ 排他的な 4 区分の合計は実労働時間に一致する。深夜は重ね掛けなので含めない
    CONSTRAINT daily_attendances_breakdown_check
        CHECK (base_minutes
               + overtime_within_statutory_minutes
               + overtime_beyond_statutory_minutes
               + legal_holiday_minutes = working_minutes),

    -- ★ 深夜労働が実労働時間を超えることはありえない
    CONSTRAINT daily_attendances_night_within_working_check
        CHECK (night_minutes <= working_minutes),

    -- ★ フレックスは日次で残業を判定しない（BR-05）
    CONSTRAINT daily_attendances_flex_check
        CHECK (working_time_system <> 'FLEX'
               OR (overtime_within_statutory_minutes = 0
                   AND overtime_beyond_statutory_minutes = 0)),

    -- ★ 休憩の充足は実労働時間と休憩時間から一意に決まる（労基法 34 条 / BR-08）
    CONSTRAINT daily_attendances_break_requirement_check
        CHECK (break_requirement_satisfied = (
            CASE WHEN working_minutes > 480 THEN break_minutes >= 60
                 WHEN working_minutes > 360 THEN break_minutes >= 45
                 ELSE true END)),

    CONSTRAINT daily_attendances_employee_date_uk UNIQUE (employee_id, work_date)
);

CREATE TABLE daily_attendance_slices (
    id                  uuid        PRIMARY KEY,
    daily_attendance_id uuid        NOT NULL
                                    REFERENCES daily_attendances (id) ON DELETE CASCADE,
    sequence_no         int         NOT NULL,
    calendar_date       date        NOT NULL,
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

    CONSTRAINT daily_attendance_slices_order_uk UNIQUE (daily_attendance_id, sequence_no),

    -- calendar_date は「その区間が属する暦日」。開始時刻から一意に決まる。
    -- 固定オフセットを使うのは、CHECK 制約の式が IMMUTABLE でなければならないため。
    -- timestamptz AT TIME ZONE '<ゾーン名>' は STABLE（ゾーン定義が変わりうる）だが、
    -- AT TIME ZONE INTERVAL は IMMUTABLE。日本標準時に夏時間は無いので +09:00 で厳密に等しい
    CONSTRAINT daily_attendance_slices_calendar_date_check
        CHECK (calendar_date = (started_at AT TIME ZONE INTERVAL '+09:00')::date),

    -- 区間は暦日境界で分割されるので、1 区間が 2 つの暦日にまたがることはない。
    -- 半開区間なので、終了が翌日 0:00 ちょうどになるのは正当
    CONSTRAINT daily_attendance_slices_single_day_check
        CHECK ((ended_at AT TIME ZONE INTERVAL '+09:00')
                   <= (calendar_date + 1)::timestamp),

    -- ★ 同じ日の内訳どうしが重ならない。
    --   重なると、その分が両方の区間に計上されて労働時間が二重になる
    --   （CLAUDE.md 落とし穴 32）。ドメインの不変条件を DB でも表現する
    CONSTRAINT daily_attendance_slices_no_overlap
        EXCLUDE USING gist (
            daily_attendance_id WITH =,
            tstzrange(started_at, ended_at) WITH &&
        )
);

CREATE OR REPLACE FUNCTION daily_attendances_touch() RETURNS trigger AS $$
BEGIN
    NEW.updated_at    = now();
    NEW.calculated_at = now();   -- DEFAULT now() は INSERT 時にしか効かない
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER daily_attendances_touch_trigger BEFORE UPDATE ON daily_attendances
    FOR EACH ROW EXECUTE FUNCTION daily_attendances_touch();
