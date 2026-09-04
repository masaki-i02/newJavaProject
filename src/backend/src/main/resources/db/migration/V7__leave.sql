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
    -- 人事が申告した出勤扱いの日数（休業・BR-14）。理由は日数が 1 以上なら必須
    deemed_attended_days integer   NOT NULL DEFAULT 0,
    deemed_reason      text,
    assessed_at        timestamptz NOT NULL,
    -- 行の作成時に 1 を入れる。0 は「行が無い」ことだけを指す（05 と同じ約束）
    version            bigint      NOT NULL DEFAULT 1,
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

    -- 出勤率の分子は分母を超えない。出勤扱いを足しても超えない
    CONSTRAINT paid_leave_grants_rate_check
        CHECK (total_working_days >= 0 AND attended_days >= 0
               AND deemed_attended_days >= 0
               AND attended_days + deemed_attended_days <= total_working_days),
    -- ★ 出勤扱いを申告したなら理由が要る。空文字・空白のみも認めない
    CONSTRAINT paid_leave_grants_deemed_reason_check
        CHECK (deemed_attended_days = 0
               OR length(btrim(coalesce(deemed_reason, ''))) > 0),

    -- ★ 冪等性の根拠。付与処理を 2 回実行しても二重に付与されない
    CONSTRAINT paid_leave_grants_employee_index_uk UNIQUE (employee_id, grant_index),
    CONSTRAINT paid_leave_grants_employee_date_uk  UNIQUE (employee_id, granted_on),
    -- ★ 配分先が「本人の付与」であることを申請側から参照するための複合キー（3.2）
    CONSTRAINT paid_leave_grants_id_employee_uk    UNIQUE (id, employee_id)
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
    grant_id     uuid,
    decided_by   uuid        REFERENCES employees (id),
    decided_at   timestamptz,
    comment      text,
    -- 取り消した人。本人（取下げ）か、取得日の当日以降なら HR（BR-16）
    canceled_by  uuid        REFERENCES employees (id),
    canceled_at  timestamptz,
    -- 行の作成時に 1 を入れる。0 は「行が無い」ことだけを指す（05 と同じ約束）
    version      bigint      NOT NULL DEFAULT 1,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT paid_leave_requests_status_check
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELED')),

    -- ★ 状態ごとに、埋まっているべき列と空であるべき列を規定する
    CONSTRAINT paid_leave_requests_state_check CHECK (
        (status = 'SUBMITTED'
             AND grant_id IS NULL AND decided_by IS NULL AND decided_at IS NULL
             AND comment IS NULL AND canceled_by IS NULL AND canceled_at IS NULL)
        -- 承認済みは必ず配分先を持つ。「消化したのにどの付与から引いたか不明」を作らない
        OR (status = 'APPROVED'
             AND grant_id IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL
             AND canceled_by IS NULL AND canceled_at IS NULL)
        -- 却下はコメントが必須。空文字・空白のみも認めない
        OR (status = 'REJECTED'
             AND grant_id IS NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL
             AND length(btrim(coalesce(comment, ''))) > 0
             AND canceled_by IS NULL AND canceled_at IS NULL)
        -- 取消は配分を外す。承認済みだった場合の decided_by / decided_at は残す
        OR (status = 'CANCELED'
             AND grant_id IS NULL
             AND canceled_by IS NOT NULL AND canceled_at IS NOT NULL)
    ),

    -- ★ 自己承認・自己却下の禁止（BR-11）
    CONSTRAINT paid_leave_requests_no_self_decision_check
        CHECK (decided_by IS NULL OR decided_by <> employee_id),

    -- ★ 本人以外が取り消す（人事による当日以降の取消・BR-16）なら理由が要る
    CONSTRAINT paid_leave_requests_revoke_reason_check
        CHECK (canceled_by IS NULL OR canceled_by = employee_id
               OR length(btrim(coalesce(comment, ''))) > 0),

    -- ★ 配分先は「本人の付与」でなければならない。
    --   単純な id への外部キーだと、他人の付与から自分の年休を消化する行が作れる
    CONSTRAINT paid_leave_requests_grant_fk
        FOREIGN KEY (grant_id, employee_id)
        REFERENCES paid_leave_grants (id, employee_id),

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
        CHECK (event_kind IN ('SUBMIT', 'APPROVE', 'REJECT', 'CANCEL', 'REVOKE')),

    -- ★ 起きうる遷移だけを記録できる。締め済みと同じく、決裁済みからは戻らない
    CONSTRAINT paid_leave_request_events_transition_check CHECK (
        (from_status = 'NONE'      AND to_status = 'SUBMITTED' AND event_kind = 'SUBMIT')
        OR (from_status = 'SUBMITTED' AND to_status = 'APPROVED'  AND event_kind = 'APPROVE')
        OR (from_status = 'SUBMITTED' AND to_status = 'REJECTED'  AND event_kind = 'REJECT')
        OR (from_status = 'SUBMITTED' AND to_status = 'CANCELED'  AND event_kind = 'CANCEL')
        -- 承認済みの取消。取得日の前日まで本人が行える（BR-16）
        OR (from_status = 'APPROVED'  AND to_status = 'CANCELED'  AND event_kind = 'CANCEL')
        -- 取得日の当日以降に人事が取り消した（BR-16）。理由が必須
        OR (from_status = 'APPROVED'  AND to_status = 'CANCELED'  AND event_kind = 'REVOKE')
    ),

    CONSTRAINT paid_leave_request_events_reason_check
        CHECK (event_kind NOT IN ('REJECT', 'REVOKE')
               OR length(btrim(coalesce(comment, ''))) > 0)
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
