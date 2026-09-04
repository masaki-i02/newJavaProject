package jp.co.sample.kintai.leave.domain;

/**
 * 年休の申請の状態（BR-16）。
 *
 * <p>状態ごとに持つべきデータが変わらないので {@code enum} にする。
 * 月次勤怠（{@code MonthlyAttendanceStatus}）が {@code sealed interface} なのは、
 * 状態ごとに承認者・締めた人などの項目が違うからで、性質が異なる。
 */
public enum LeaveRequestStatus {

    /** 申請中。まだ年休を消費していない。 */
    SUBMITTED,

    /** 承認済み。付与へ配分されている。 */
    APPROVED,

    /** 却下。<strong>理由が必須。</strong> */
    REJECTED,

    /** 取下げ（本人）または取消（人事）。配分は外れる。 */
    CANCELED
}
