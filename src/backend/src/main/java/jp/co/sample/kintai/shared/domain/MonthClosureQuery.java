package jp.co.sample.kintai.shared.domain;

import java.time.YearMonth;

/**
 * 月次勤怠が締められているかを問い合わせるポート。
 *
 * <p>締め状態を持つのは {@code approval} だが、それを知りたいのは
 * {@code attendance}・{@code workrule}・{@code employee} である。
 * 素直に問い合わせると依存が循環するので、
 * <strong>ポートを {@code shared} に置き、実装を {@code approval/infrastructure} に置く</strong>
 * （ADR 0004）。これで依存図に新しい辺が 1 本も増えない。
 *
 * <p>判定に使う月は<strong>勤務日が属する月</strong>である。
 * 打刻時刻の月で判定すると、3/31 22:00 出勤 → 4/1 06:00 退勤の退勤打刻が、
 * 締め済みの 3 月分を 4 月扱いで書き込めてしまう。
 */
public interface MonthClosureQuery {

    /** 締め済みか。 */
    boolean isClosed(EmployeeId employeeId, YearMonth month);

    /**
     * 本人が直接打刻してよい状態か。<strong>再計算とカレンダーの変更もこれで判定する。</strong>
     *
     * <p><strong>「訂正申請を受け付けるか」と分ける。</strong>
     * 1 つにまとめると、提出済みの月で {@code true} を返すことになり、
     * <strong>本人が提出後に直接打刻できてしまう。</strong>
     * 月次勤怠は提出済みのまま内容だけが変わり、
     * 承認者が確認した内容と実際に確定される内容が食い違う
     * （申請・承認と締め ドメインモデル設計書 2.1）。
     */
    boolean acceptsTimeClock(EmployeeId employeeId, YearMonth month);

    /**
     * 訂正申請を受け付けてよい状態か。
     *
     * <p>提出済みでも受け付ける。差戻しを待たずに、気づいた誤りを直せるようにする。
     */
    boolean acceptsCorrectionRequest(EmployeeId employeeId, YearMonth month);

    /**
     * <strong>意思表示（訂正申請・年休）を受け付けてよい月か。</strong>
     *
     * <p>訂正申請と年休が、同じ本文を別々に書いていた。
     * どちらも「締め済みなら {@code month-already-closed}、
     * 承認済みなら {@code month-not-editable}」であり、
     * <strong>順序そのものが業務上の決定</strong>である
     * （締め済みは取り消せないが、承認済みは承認を取り消せば直せる。
     * 逆に並べると、締め済みの月に「承認を取り消せば直せます」と案内してしまう）。
     *
     * <p>写すと片方だけが古くなるので、判定はここに 1 つだけ置く（落とし穴 67）。
     */
    default void requireEditable(EmployeeId employeeId, YearMonth month) {
        if (isClosed(employeeId, month)) {
            throw new MonthAlreadyClosedException(month);
        }
        if (!acceptsCorrectionRequest(employeeId, month)) {
            throw new MonthNotEditableException(month);
        }
    }

    /** 締め済みの月への意思表示。<strong>締めは取り消せない。</strong> */
    final class MonthAlreadyClosedException extends DomainException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        MonthAlreadyClosedException(YearMonth month) {
            super("締め済みの月は変更できません: " + month);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-already-closed";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "締め済みの月です";
        }
    }

    /**
     * 承認済みの月への意思表示。
     *
     * <p><strong>締め済みと分ける。</strong> 承認済みは承認を取り消せば直せるので、
     * 利用者への案内がまったく違う。
     */
    final class MonthNotEditableException extends DomainException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        MonthNotEditableException(YearMonth month) {
            super("承認済みの月は変更できません: " + month);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-not-editable";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "承認済みの月です";
        }
    }

    /**
     * その月を締めた社員が<strong>1 人でもいるか</strong>。
     *
     * <p><strong>会社カレンダーの変更に使う。</strong>
     * 暦日区分は全社で共有する 1 つの表なので、社員ごとの判定では足りない。
     * 誰か 1 人でも締めた月の暦日区分を変えると、
     * 休日割増の計算が変わり<strong>確定済みの勤怠と矛盾する。</strong>
     *
     * <p>社員を指定できる変更（就業規則の適用）は
     * {@link #isClosed(EmployeeId, YearMonth)} で判定すればよい。
     */
    boolean isClosedForAnyone(YearMonth month);
}
