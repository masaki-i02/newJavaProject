-- 申請・承認・締め
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/05_申請承認と締め/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

CREATE TABLE monthly_attendances (
    id           uuid        PRIMARY KEY,
    employee_id  uuid        NOT NULL REFERENCES employees (id),
    target_month date        NOT NULL,
    status       varchar(20) NOT NULL,
    submitted_at timestamptz,
    submitted_by uuid        REFERENCES employees (id),
    approved_by  uuid        REFERENCES employees (id),
    approved_at  timestamptz,
    closed_by    uuid        REFERENCES employees (id),
    closed_at    timestamptz,
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT monthly_attendances_status_check
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'CLOSED')),
    -- 対象月は必ず月初日で表現する
    CONSTRAINT monthly_attendances_month_check
        CHECK (target_month = date_trunc('month', target_month)::date),

    -- ★ 状態ごとに、埋まっているべき列と空であるべき列を規定する。
    --   ドメインの sealed interface（Draft / Submitted / Approved / Closed）に対応
    CONSTRAINT monthly_attendances_state_check CHECK (
        (status = 'DRAFT'
             AND submitted_at IS NULL AND submitted_by IS NULL
             AND approved_by IS NULL AND approved_at IS NULL
             AND closed_by IS NULL AND closed_at IS NULL)
        OR (status = 'SUBMITTED'
             AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL
             AND approved_by IS NULL AND approved_at IS NULL
             AND closed_by IS NULL AND closed_at IS NULL)
        OR (status = 'APPROVED'
             AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL
             AND approved_by IS NOT NULL AND approved_at IS NOT NULL
             AND closed_by IS NULL AND closed_at IS NULL)
        OR (status = 'CLOSED'
             AND submitted_at IS NOT NULL AND submitted_by IS NOT NULL
             AND approved_by IS NOT NULL AND approved_at IS NOT NULL
             AND closed_by IS NOT NULL AND closed_at IS NOT NULL)
    ),

    -- ★ 自己承認の禁止（BR-11 の 4）
    CONSTRAINT monthly_attendances_no_self_approval_check
        CHECK (approved_by IS NULL OR approved_by <> employee_id),

    -- 時系列の整合。承認は提出より後、締めは承認より後
    CONSTRAINT monthly_attendances_approved_after_submitted_check
        CHECK (approved_at IS NULL OR submitted_at IS NULL OR approved_at >= submitted_at),
    CONSTRAINT monthly_attendances_closed_after_approved_check
        CHECK (closed_at IS NULL OR approved_at IS NULL OR closed_at >= approved_at),

    CONSTRAINT monthly_attendances_employee_month_uk UNIQUE (employee_id, target_month)
);

-- 承認待ちの一覧を引く。承認者は「自分が承認すべきもの」を毎回探すため
CREATE INDEX monthly_attendances_pending_idx
    ON monthly_attendances (target_month, employee_id) WHERE status = 'SUBMITTED';

CREATE TABLE approval_events (
    id                    uuid         PRIMARY KEY,
    monthly_attendance_id uuid         NOT NULL REFERENCES monthly_attendances (id),
    from_status           varchar(20)  NOT NULL,
    to_status             varchar(20)  NOT NULL,
    event_kind            varchar(30)  NOT NULL,
    actor_id              uuid         NOT NULL REFERENCES employees (id),
    comment               varchar(500),
    occurred_at           timestamptz  NOT NULL,
    created_at            timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT approval_events_from_status_check
        CHECK (from_status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'CLOSED')),
    CONSTRAINT approval_events_to_status_check
        CHECK (to_status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'CLOSED')),
    -- ★ 許可される遷移の組をすべて列挙する。
    --   ドメインの AttendanceTransition（6 種）と 1 対 1 に対応する
    CONSTRAINT approval_events_transition_check CHECK (
        (from_status, to_status) IN (
            ('DRAFT',     'SUBMITTED'),   -- Submit
            ('SUBMITTED', 'APPROVED'),    -- Approve
            ('SUBMITTED', 'DRAFT'),       -- Reject / RevertByCorrection
            ('APPROVED',  'CLOSED'),      -- Close
            ('APPROVED',  'DRAFT')        -- RevokeApproval
        )
    ),

    -- ★ 差戻し・承認の取消・訂正承認による自動差戻しは理由が必須（BR-10）。
    --   空文字と空白も許さない
    CONSTRAINT approval_events_reason_required_check
        CHECK (to_status <> 'DRAFT' OR length(btrim(coalesce(comment, ''))) > 0),

    -- ★ 代理提出は理由が必須
    CONSTRAINT approval_events_proxy_reason_check
        CHECK (to_status <> 'SUBMITTED' OR event_kind <> 'PROXY_SUBMIT'
               OR length(btrim(coalesce(comment, ''))) > 0),

    CONSTRAINT approval_events_kind_check
        CHECK (event_kind IN ('SUBMIT', 'PROXY_SUBMIT', 'APPROVE', 'REJECT',
                              'CLOSE', 'REVOKE_APPROVAL', 'REVERT_BY_CORRECTION'))
);

CREATE INDEX approval_events_target_idx
    ON approval_events (monthly_attendance_id, occurred_at);
-- 監査時に「誰が何をしたか」を実行者から追う（4.4）
CREATE INDEX approval_events_actor_idx ON approval_events (actor_id, occurred_at);

CREATE TABLE time_clock_correction_requests (
    id               uuid         PRIMARY KEY,
    employee_id      uuid         NOT NULL REFERENCES employees (id),
    work_date        date         NOT NULL,
    status           varchar(20)  NOT NULL,
    reason           varchar(500) NOT NULL,
    requested_at     timestamptz  NOT NULL,
    decided_by       uuid         REFERENCES employees (id),
    decided_at       timestamptz,
    decision_comment varchar(500),
    version          bigint       NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT correction_requests_status_check
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELED')),
    -- 申請理由は必須。空文字も許さない
    CONSTRAINT correction_requests_reason_check
        CHECK (length(btrim(reason)) > 0),

    -- 状態と決裁情報の整合
    CONSTRAINT correction_requests_state_check CHECK (
        (status = 'SUBMITTED' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED')
             AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
        -- 取下げは本人が行うので、決裁者は本人になる
        OR (status = 'CANCELED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),
    -- 却下は理由が必須
    CONSTRAINT correction_requests_rejection_comment_check
        CHECK (status <> 'REJECTED' OR length(btrim(coalesce(decision_comment, ''))) > 0),
    -- ★ 自分の訂正を自分で承認・却下できない。取下げだけは本人が行う
    CONSTRAINT correction_requests_no_self_approval_check
        CHECK (decided_by IS NULL
               OR status = 'CANCELED'
               OR decided_by <> employee_id),
    CONSTRAINT correction_requests_cancel_by_self_check
        CHECK (status <> 'CANCELED' OR decided_by = employee_id),
    CONSTRAINT correction_requests_decided_after_requested_check
        CHECK (decided_at IS NULL OR decided_at >= requested_at)
);

-- ★ 同一勤務日に未処理の申請は 1 件まで。
--   競合する訂正が同時に承認されると、打刻列が壊れる
CREATE UNIQUE INDEX correction_requests_pending_uk
    ON time_clock_correction_requests (employee_id, work_date)
    WHERE status = 'SUBMITTED';

CREATE INDEX correction_requests_employee_idx
    ON time_clock_correction_requests (employee_id, work_date DESC);

CREATE TABLE time_clock_correction_items (
    id              uuid        PRIMARY KEY,
    request_id      uuid        NOT NULL
                                REFERENCES time_clock_correction_requests (id) ON DELETE CASCADE,
    sequence_no     int         NOT NULL,
    action          varchar(20) NOT NULL,
    work_date       date        NOT NULL,
    target_event_id uuid,
    event_type      varchar(20),
    occurred_at     timestamptz,

    CONSTRAINT correction_items_action_check
        CHECK (action IN ('REVOKE', 'ADD')),
    CONSTRAINT correction_items_event_type_check
        CHECK (event_type IS NULL
               OR event_type IN ('CLOCK_IN', 'CLOCK_OUT', 'BREAK_START', 'BREAK_END')),

    -- ★ 操作ごとに必要な列が揃い、不要な列が NULL であること。
    --   ドメインの sealed interface（Revoke / Add）に対応
    CONSTRAINT correction_items_variant_check CHECK (
        (action = 'REVOKE'
             AND target_event_id IS NOT NULL
             AND event_type IS NULL AND occurred_at IS NULL)
        OR
        (action = 'ADD'
             AND target_event_id IS NULL
             AND event_type IS NOT NULL AND occurred_at IS NOT NULL)
    ),

    -- 取消対象は実在する打刻でなければならない。
    -- time_clock_events の主キーが (work_date, id) の複合なので work_date を持つ
    CONSTRAINT correction_items_target_fk
        FOREIGN KEY (work_date, target_event_id)
        REFERENCES time_clock_events (work_date, id),

    CONSTRAINT correction_items_order_uk UNIQUE (request_id, sequence_no)
);

CREATE TRIGGER monthly_attendances_set_updated_at BEFORE UPDATE ON monthly_attendances
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER correction_requests_set_updated_at
    BEFORE UPDATE ON time_clock_correction_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
