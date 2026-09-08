-- 通しのシナリオの前提データ。
--
--   psql -h 127.0.0.1 -p 55432 -d kintai_e2e -f e2e/seed.sql
--
-- ★ スキーマは Flyway が作る。ここは行を入れるだけ。
-- ★ パスワードは BCrypt のハッシュ（平文は correct-horse-battery）。
--   DB の CHECK が $2[aby]$ で始まる 60 文字以外を拒否する。

BEGIN;

-- ★ 打刻と日次勤怠を先に消す。
--   消さないと 2 回目の実行で「すでに退勤済み」から始まり、
--   IT-SCN-32（未出勤 → 出勤 → 休憩 → 退勤）が成立しない。
--   **通せる回数が 1 回だけのシナリオは、通らなくなった理由を切り分けられない。**
--   バックエンドのテストが @BeforeEach で TRUNCATE するのと同じ理由である。
--
--   社員の行そのものは消さない（ON CONFLICT DO NOTHING で足りる）。
--   消すと社員番号の再割り当てを跨いだ検証ができなくなる。
DELETE FROM daily_attendances
 WHERE employee_id IN ('00000000-0000-4000-8000-000000000001',
                       '00000000-0000-4000-8000-000000000100');
DELETE FROM time_clock_events
 WHERE employee_id IN ('00000000-0000-4000-8000-000000000001',
                       '00000000-0000-4000-8000-000000000100');

-- ★ シナリオが作る就業規則も消す。
--   残すと 2 回目の実行で「同じ名前の系列が 2 つ」から始まり、
--   一覧の件数を確かめるシナリオが製品の欠陥のように見える形で落ちる
--   （落とし穴 153 と同じ理由）。版から先に消す（外部キー）。
DELETE FROM work_rules
 WHERE series_id IN (SELECT id FROM work_rule_series WHERE name = 'シナリオ用規則');
DELETE FROM work_rule_series WHERE name = 'シナリオ用規則';

-- ★ シナリオが書き換える暦日を戻す。ON CONFLICT DO NOTHING では戻らない
UPDATE company_calendars SET day_type = 'WORKDAY', name = NULL
 WHERE calendar_date = DATE '2026-04-29';

-- 一般社員と部署長
INSERT INTO employees (id, employee_number, name, email, hired_on)
VALUES ('00000000-0000-4000-8000-000000000001', 'E0001', '山田 太郎',
        'e0001@example.com', DATE '2026-04-01'),
       ('00000000-0000-4000-8000-000000000100', 'E0100', '佐藤 課長',
        'e0100@example.com', DATE '2026-04-01'),
       ('00000000-0000-4000-8000-000000000900', 'E0900', '人事 花子',
        'e0900@example.com', DATE '2026-04-01')
ON CONFLICT DO NOTHING;

-- ★ APPROVER はここに入れない。認証の時点で「その日に部署長か」から導出される。
--   ロールとして別に持つと「部署長だがロールが無く 403」が起きる
INSERT INTO employee_roles (employee_id, role)
VALUES ('00000000-0000-4000-8000-000000000001', 'EMPLOYEE'),
       ('00000000-0000-4000-8000-000000000100', 'EMPLOYEE'),
       ('00000000-0000-4000-8000-000000000900', 'EMPLOYEE'),
       ('00000000-0000-4000-8000-000000000900', 'HR')
ON CONFLICT DO NOTHING;

INSERT INTO employee_credentials (employee_id, password_hash, password_changed_at)
VALUES ('00000000-0000-4000-8000-000000000001',
        '$2a$10$4aS6WjvHoMuNk9iAPex7uOCasl8rw.2ZJkGorqAz5BBei9O9SfTxG',
        TIMESTAMPTZ '2026-04-01 09:00:00+09'),
       ('00000000-0000-4000-8000-000000000100',
        '$2a$10$4aS6WjvHoMuNk9iAPex7uOCasl8rw.2ZJkGorqAz5BBei9O9SfTxG',
        TIMESTAMPTZ '2026-04-01 09:00:00+09'),
       ('00000000-0000-4000-8000-000000000900',
        '$2a$10$4aS6WjvHoMuNk9iAPex7uOCasl8rw.2ZJkGorqAz5BBei9O9SfTxG',
        TIMESTAMPTZ '2026-04-01 09:00:00+09')
ON CONFLICT DO NOTHING;

-- ★ 承認者は組織から導かれる（BR-11）。部署と部署長が無いと承認できない
INSERT INTO departments (id, code, name, parent_id)
VALUES ('00000000-0000-4000-8000-0000000000d1', 'SALES', '営業部', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO assignments (id, employee_id, department_id, valid_from)
VALUES (gen_random_uuid(), '00000000-0000-4000-8000-000000000001',
        '00000000-0000-4000-8000-0000000000d1', DATE '2026-04-01'),
       (gen_random_uuid(), '00000000-0000-4000-8000-000000000100',
        '00000000-0000-4000-8000-0000000000d1', DATE '2026-04-01'),
       (gen_random_uuid(), '00000000-0000-4000-8000-000000000900',
        '00000000-0000-4000-8000-0000000000d1', DATE '2026-04-01')
ON CONFLICT DO NOTHING;

INSERT INTO managerships (id, department_id, employee_id, valid_from)
VALUES (gen_random_uuid(), '00000000-0000-4000-8000-0000000000d1',
        '00000000-0000-4000-8000-000000000100', DATE '2026-04-01')
ON CONFLICT DO NOTHING;

-- 就業規則（固定時間制 9:00–18:00・休憩 60 分）
INSERT INTO work_rule_series (id, name)
VALUES ('00000000-0000-4000-8000-0000000000f1', '標準勤務')
ON CONFLICT DO NOTHING;

INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes)
VALUES ('00000000-0000-4000-8000-0000000000f2',
        '00000000-0000-4000-8000-0000000000f1', 'FIXED', DATE '2026-04-01',
        TIME '09:00', TIME '18:00', 60)
ON CONFLICT DO NOTHING;

INSERT INTO work_rule_assignments (id, employee_id, work_rule_series_id, valid_from)
VALUES (gen_random_uuid(), '00000000-0000-4000-8000-000000000001',
        '00000000-0000-4000-8000-0000000000f1', DATE '2026-04-01'),
       (gen_random_uuid(), '00000000-0000-4000-8000-000000000100',
        '00000000-0000-4000-8000-0000000000f1', DATE '2026-04-01'),
       (gen_random_uuid(), '00000000-0000-4000-8000-000000000900',
        '00000000-0000-4000-8000-0000000000f1', DATE '2026-04-01')
ON CONFLICT DO NOTHING;

-- 会社カレンダー（土=所定休日・日=法定休日）。
-- ★ 未登録の日は所定労働日として扱われる。給与連携は全日登録を要求する
INSERT INTO company_calendars (calendar_date, day_type, name)
SELECT d::date,
       CASE EXTRACT(DOW FROM d)
            WHEN 0 THEN 'LEGAL_HOLIDAY'
            WHEN 6 THEN 'NON_LEGAL_HOLIDAY'
            ELSE 'WORKDAY' END,
       NULL
  FROM generate_series(DATE '2026-04-01', DATE '2027-03-31', '1 day') d
ON CONFLICT (calendar_date) DO NOTHING;

COMMIT;
