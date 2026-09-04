package jp.co.sample.kintai.approval.domain;

import java.time.LocalDateTime;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 月次勤怠の状態（BR-10）。
 *
 * <p><strong>状態ごとに持つ項目が違うので {@code sealed interface} にする。</strong>
 * 「承認済みなのに承認者が不明」という状態を型として作れなくする。
 * 同じ不変条件を DB の {@code monthly_attendances_state_check} でも守る。
 *
 * <p><strong>「打刻」と「訂正申請」を分ける。</strong>
 * 1 つの {@code acceptsChanges()} にまとめると、提出済みが真を返すので
 * <strong>本人が提出後に直接打刻できてしまう。</strong>
 * 月次勤怠は提出済みのまま内容だけが変わり、
 * 承認者が確認した内容と実際に確定される内容が食い違う。
 */
public sealed interface MonthlyAttendanceStatus {

    /** 本人が直接打刻してよい状態か。 */
    boolean acceptsTimeClock();

    /** 訂正申請を受け付けてよい状態か。 */
    boolean acceptsCorrectionRequest();

    /** 永続化と表示のための判別値。 */
    AttendanceState state();

    /** 下書き。まだ誰も見ていない。 */
    record Draft() implements MonthlyAttendanceStatus {

        @Override
        public boolean acceptsTimeClock() {
            return true;
        }

        @Override
        public boolean acceptsCorrectionRequest() {
            return true;
        }

        @Override
        public AttendanceState state() {
            return AttendanceState.DRAFT;
        }
    }

    /** 提出済。承認者が見ている内容を勝手に変えない。直したいなら申請する。 */
    record Submitted(EmployeeId submittedBy, LocalDateTime submittedAt)
            implements MonthlyAttendanceStatus {

        public Submitted {
            if (submittedBy == null || submittedAt == null) {
                throw new IllegalArgumentException("提出済には提出者と提出日時が要ります");
            }
        }

        @Override
        public boolean acceptsTimeClock() {
            return false;
        }

        @Override
        public boolean acceptsCorrectionRequest() {
            return true;
        }

        @Override
        public AttendanceState state() {
            return AttendanceState.SUBMITTED;
        }
    }

    /** 承認済。確定した。変えるには承認を取り消す。 */
    record Approved(EmployeeId submittedBy, LocalDateTime submittedAt,
                    EmployeeId approvedBy, LocalDateTime approvedAt)
            implements MonthlyAttendanceStatus {

        public Approved {
            if (submittedBy == null || submittedAt == null
                    || approvedBy == null || approvedAt == null) {
                throw new IllegalArgumentException("承認済には提出と承認の記録が要ります");
            }
            // 自己承認の禁止（BR-11 の 4）はここでは検査しない。
            // 禁じたいのは「本人が自分を承認すること」であり、提出者との比較ではない。
            // 代理提出では提出者（人事）と承認者が別人でも、対象の社員が承認していれば違反になる。
            // 対象の社員 ID を持つ MonthlyAttendance が判定する
            if (approvedAt.isBefore(submittedAt)) {
                throw new IllegalArgumentException(
                        "承認は提出より後である必要があります: 提出 %s / 承認 %s"
                                .formatted(submittedAt, approvedAt));
            }
        }

        @Override
        public boolean acceptsTimeClock() {
            return false;
        }

        @Override
        public boolean acceptsCorrectionRequest() {
            return false;
        }

        @Override
        public AttendanceState state() {
            return AttendanceState.APPROVED;
        }
    }

    /** 締め済。<strong>ここからの遷移は定義しない。</strong> 確定値が動かないことを型で保証する。 */
    record Closed(EmployeeId submittedBy, LocalDateTime submittedAt,
                  EmployeeId approvedBy, LocalDateTime approvedAt,
                  EmployeeId closedBy, LocalDateTime closedAt)
            implements MonthlyAttendanceStatus {

        public Closed {
            if (submittedBy == null || submittedAt == null || approvedBy == null
                    || approvedAt == null || closedBy == null || closedAt == null) {
                throw new IllegalArgumentException("締め済には提出・承認・締めの記録が要ります");
            }
            // Approved が守っている不変条件を、ここでも守る。
            // close() を経ればかならず成り立つが、この型は永続化アダプタが
            // DB の行から直接組み立てる。壊れた行をそのまま通さない
            if (approvedAt.isBefore(submittedAt)) {
                throw new IllegalArgumentException(
                        "承認は提出より後である必要があります: 提出 %s / 承認 %s"
                                .formatted(submittedAt, approvedAt));
            }
            if (closedAt.isBefore(approvedAt)) {
                throw new IllegalArgumentException(
                        "締めは承認より後である必要があります: 承認 %s / 締め %s"
                                .formatted(approvedAt, closedAt));
            }
        }

        @Override
        public boolean acceptsTimeClock() {
            return false;
        }

        @Override
        public boolean acceptsCorrectionRequest() {
            return false;
        }

        @Override
        public AttendanceState state() {
            return AttendanceState.CLOSED;
        }
    }
}
