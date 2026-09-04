-- 年次有給休暇
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/06_年次有給休暇/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

CREATE TABLE paid_leave_grants (
    id                 uuid        PRIMARY KEY,
    employee_id        uuid        NOT NULL REFERENCES employees (id),
    -- 何回目の付与か（0 起点）。0 = 入社 6 か月後。継続勤務年数の表（BR-14）を引く鍵
    grant_index        integer     NOT NULL,
    granted_on         date        NOT NULL,
    -- 付与したか。false は「出勤率 8 割に満たなかった」（BR-14）
    granted            boolean     NOT NULL,
    days               integer,
    -- 出勤率の根拠。なぜ不付与だったかを後から説明するために残す
    total_working_days integer     NOT NULL,
    attended_days      integer     NOT NULL,
    assessed_at        timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT paid_leave_grants_index_check CHECK (grant_index >= 0),

    -- ★ 付与したかどうかと日数の整合。ドメインの GrantDecision（sealed）に対応する。
    --   「不付与なのに日数がある」「付与したのに日数が無い」を作れなくする
    CONSTRAINT paid_leave_grants_decision_check CHECK (
        (granted AND days IS NOT NULL) OR (NOT granted AND days IS NULL)
    ),
    -- ★ 法定の表にある日数しか存在しない（BR-14）。上限も置く（落とし穴 15）
    CONSTRAINT paid_leave_grants_days_check
        CHECK (days IS NULL OR days BETWEEN 10 AND 20),

    -- 出勤率の分子は分母を超えない
    CONSTRAINT paid_leave_grants_rate_check
        CHECK (total_working_days >= 0 AND attended_days >= 0
               AND attended_days <= total_working_days),

    -- ★ 冪等性の根拠。付与処理を 2 回実行しても二重に付与されない
    CONSTRAINT paid_leave_grants_employee_index_uk UNIQUE (employee_id, grant_index),
    CONSTRAINT paid_leave_grants_employee_date_uk  UNIQUE (employee_id, granted_on)
);

-- 残日数は「その日に有効な付与」を古い順に読む（4.1）
CREATE INDEX paid_leave_grants_employee_granted_on_idx
    ON paid_leave_grants (employee_id, granted_on);

CREATE TABLE paid_leave_requests (
    id           uuid        PRIMARY KEY,
    employee_id  uuid        NOT NULL REFERENCES employees (id),
    -- 取得日。1 申請 1 日（BR-16）
    leave_date   date        NOT NULL,
    reason       text,
    status       varchar(20) NOT NULL,
    requested_at timestamptz NOT NULL,
    -- 承認時に確定する配分先。先入先出で選ばれた付与（BR-15）
    grant_id     uuid        REFERENCES paid_leave_grants (id),
    decided_by   uuid        REFERENCES employees (id),
    decided_at   timestamptz,
    comment      text,
    canceled_at  timestamptz,
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT paid_leave_requests_status_check
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELED')),

    -- ★ 状態ごとに、埋まっているべき列と空であるべき列を規定する
    CONSTRAINT paid_leave_requests_state_check CHECK (
        (status = 'SUBMITTED'
             AND grant_id IS NULL AND decided_by IS NULL AND decided_at IS NULL
             AND comment IS NULL AND canceled_at IS NULL)
        -- 承認済みは必ず配分先を持つ。「消化したのにどの付与から引いたか不明」を作らない
        OR (status = 'APPROVED'
             AND grant_id IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL
             AND canceled_at IS NULL)
        -- 却下はコメントが必須。空文字・空白のみも認めない
        OR (status = 'REJECTED'
             AND grant_id IS NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL
             AND length(btrim(coalesce(comment, ''))) > 0
             AND canceled_at IS NULL)
        -- 取下げは本人が行うので decided_by を持たない。配分は外れる
        OR (status = 'CANCELED'
             AND grant_id IS NULL AND decided_by IS NULL AND decided_at IS NULL
             AND canceled_at IS NOT NULL)
    ),

    -- ★ 自己承認・自己却下の禁止（BR-11）
    CONSTRAINT paid_leave_requests_no_self_decision_check
        CHECK (decided_by IS NULL OR decided_by <> employee_id),

    -- 決裁は申請より後
    CONSTRAINT paid_leave_requests_decided_after_requested_check
        CHECK (decided_at IS NULL OR decided_at >= requested_at),
    CONSTRAINT paid_leave_requests_canceled_after_requested_check
        CHECK (canceled_at IS NULL OR canceled_at >= requested_at)
);

-- ★ 同じ日を二重に取得できない。却下・取下げは対象外なので再申請できる
CREATE UNIQUE INDEX paid_leave_requests_active_uk
    ON paid_leave_requests (employee_id, leave_date)
    WHERE status IN ('SUBMITTED', 'APPROVED');

-- 承認待ちの一覧（4.3）
CREATE INDEX paid_leave_requests_pending_idx
    ON paid_leave_requests (leave_date, employee_id) WHERE status = 'SUBMITTED';
-- 残日数・年 5 日の集計は承認済みだけを読む（4.1 / 4.2）
CREATE INDEX paid_leave_requests_approved_idx
    ON paid_leave_requests (employee_id, leave_date) WHERE status = 'APPROVED';

CREATE TABLE paid_leave_request_events (
    id                     uuid        PRIMARY KEY,
    paid_leave_request_id  uuid        NOT NULL REFERENCES paid_leave_requests (id),
    from_status            varchar(20) NOT NULL,
    to_status              varchar(20) NOT NULL,
    event_kind             varchar(20) NOT NULL,
    actor_id               uuid        NOT NULL REFERENCES employees (id),
    -- 却下の理由。取下げ時は、外れた配分先を記録する
    comment                text,
    occurred_at            timestamptz NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT paid_leave_request_events_kind_check
        CHECK (event_kind IN ('SUBMIT', 'APPROVE', 'REJECT', 'CANCEL')),

    -- ★ 起きうる遷移だけを記録できる。締め済みと同じく、決裁済みからは戻らない
    CONSTRAINT paid_leave_request_events_transition_check CHECK (
        (from_status = 'NONE'      AND to_status = 'SUBMITTED' AND event_kind = 'SUBMIT')
        OR (from_status = 'SUBMITTED' AND to_status = 'APPROVED'  AND event_kind = 'APPROVE')
        OR (from_status = 'SUBMITTED' AND to_status = 'REJECTED'  AND event_kind = 'REJECT')
        OR (from_status = 'SUBMITTED' AND to_status = 'CANCELED'  AND event_kind = 'CANCEL')
        -- 承認済みの取消。取得日の前日まで本人が行える（BR-16）
        OR (from_status = 'APPROVED'  AND to_status = 'CANCELED'  AND event_kind = 'CANCEL')
    ),

    CONSTRAINT paid_leave_request_events_reject_comment_check
        CHECK (event_kind <> 'REJECT' OR length(btrim(coalesce(comment, ''))) > 0)
);

CREATE INDEX paid_leave_request_events_target_idx
    ON paid_leave_request_events (paid_leave_request_id, occurred_at);
-- 監査時に「誰が何をしたか」を実行者から追う
CREATE INDEX paid_leave_request_events_actor_idx
    ON paid_leave_request_events (actor_id, occurred_at);

-- 適用済みのマイグレーション（V6）は書き換えない。Flyway のチェックサムが壊れる
ALTER TABLE approval_events DROP CONSTRAINT approval_events_kind_check;
ALTER TABLE approval_events ADD CONSTRAINT approval_events_kind_check
    CHECK (event_kind IN ('SUBMIT', 'PROXY_SUBMIT', 'APPROVE', 'REJECT',
                          'CLOSE', 'REVOKE_APPROVAL',
                          'REVERT_BY_CORRECTION', 'REVERT_BY_LEAVE'));

ALTER TABLE monthly_settlements
    ADD COLUMN paid_leave_days integer NOT NULL DEFAULT 0;
ALTER TABLE monthly_settlements
    ADD CONSTRAINT monthly_settlements_paid_leave_days_check
    CHECK (paid_leave_days >= 0);

CREATE TRIGGER paid_leave_grants_set_updated_at
    BEFORE UPDATE ON paid_leave_grants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER paid_leave_requests_set_updated_at
    BEFORE UPDATE ON paid_leave_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
