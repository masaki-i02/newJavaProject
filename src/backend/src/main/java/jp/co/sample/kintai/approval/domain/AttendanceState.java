package jp.co.sample.kintai.approval.domain;

/**
 * 月次勤怠の状態の名前。
 *
 * <p><strong>{@link MonthlyAttendanceStatus} と役割が違う。</strong>
 * あちらは状態ごとに持つ項目（提出者・承認者）を型で表すためのもので、
 * こちらは<strong>永続化と証跡のための判別値</strong>である。
 *
 * <p>監査証跡が残すのは「どの状態からどの状態へ」だけで、
 * その時点の提出者や承認者を再現する列を持たない。
 * 証跡の読み戻しに {@code MonthlyAttendanceStatus} を使おうとすると、
 * <strong>実際には残っていない情報を作り出すことになる。</strong>
 *
 * <p>DB の {@code monthly_attendances_status_check} と一対一で対応する。
 */
public enum AttendanceState {

    DRAFT,
    SUBMITTED,
    APPROVED,
    CLOSED
}
