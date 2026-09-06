-- 給与連携
--
-- このファイルは生成物である。直接編集しない。
-- 正は doc/02_詳細設計/07_給与連携/DB設計書.md であり、
-- `cd doc/_tools && python3 build-migrations.py` で生成する。

CREATE TABLE payroll_exports (
    id            uuid        PRIMARY KEY,
    -- ★ 対象月。月初日で持つ（monthly_settlements.target_month と同じ表現）
    target_month  date        NOT NULL,
    -- ★ 実行した人事担当。社員番号ではなく ID で持つ。
    --   社員番号は退職者のぶんが再利用されるので、後から別人を指す
    exported_by   uuid        NOT NULL REFERENCES employees (id),
    -- ★ 監査の時刻はアプリケーションの時計ではなく DB の時計で打つ
    exported_at   timestamptz NOT NULL DEFAULT now(),
    -- ★ この出力に使った割増賃金の基礎額の分母（労基則 19 条 1 項 4 号）と、その導出元。
    --   年度の値は後から動くので、支払の根拠として当時の値を残す。
    --   月平均だけを残すと、それが 261 日 × 480 分から出たのか
    --   260 日 × 482 分から出たのかを後から言えない
    fiscal_year             int NOT NULL,
    annual_scheduled_days   int NOT NULL,
    annual_scheduled_minutes int NOT NULL,
    monthly_average_minutes int NOT NULL,

    CONSTRAINT payroll_exports_month_check
        CHECK (target_month = date_trunc('month', target_month)::date),
    CONSTRAINT payroll_exports_average_check
        CHECK (annual_scheduled_days > 0 AND annual_scheduled_minutes > 0
               AND monthly_average_minutes > 0),
    -- ★ 導出できる値を列として持つので、食い違いを DB で禁じる（落とし穴 39）。
    --   月平均は年間の所定を 12 で割った値（分未満切り捨て）である
    CONSTRAINT payroll_exports_average_derivation_check
        CHECK (monthly_average_minutes = annual_scheduled_minutes / 12)
);

-- ★ 監査の照会は「この月を誰がいつ出したか」。対象月から引く
CREATE INDEX payroll_exports_month_idx
    ON payroll_exports (target_month, exported_at DESC);

CREATE TABLE payroll_export_targets (
    export_id   uuid    NOT NULL REFERENCES payroll_exports (id) ON DELETE CASCADE,
    employee_id uuid    NOT NULL REFERENCES employees (id),
    -- ★ NULL なら出力した社員。値が入っていれば除外した社員とその理由
    excluded_reason varchar(30),

    PRIMARY KEY (export_id, employee_id),
    CONSTRAINT payroll_export_targets_reason_check
        CHECK (excluded_reason IS NULL OR excluded_reason IN (
            'NOT_SUBMITTED', 'NOT_APPROVED', 'NOT_CLOSED',
            'NOT_SUBMITTABLE', 'NO_ATTENDANCE_RECORD', 'DUPLICATE_EMPLOYEE_NUMBER'))
);

-- ★ 除外だけを引く照会（人事が「誰が残っているか」を見る）
CREATE INDEX payroll_export_targets_excluded_idx
    ON payroll_export_targets (export_id)
    WHERE excluded_reason IS NOT NULL;
