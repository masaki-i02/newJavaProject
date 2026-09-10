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
            throw MonthAlreadyClosedException.of(month);
        }
        if (!acceptsCorrectionRequest(employeeId, month)) {
            throw new MonthNotEditableException(month);
        }
    }

    /**
     * 締め済みの月を動かそうとした。<strong>締めは取り消せない。</strong>
     *
     * <p><strong>この型は 1 つだけである。</strong>
     * 以前は {@code attendance}・{@code workrule}・{@code employee} が
     * 同じ {@code errorCode} と {@code kind} を返すクラスをそれぞれ持っており、
     * <strong>契約（型と HTTP）が 4 か所で定義されていた</strong>。
     * 画面は {@code type} で分岐する（落とし穴 182）ので、
     * どれか 1 つの {@code kind()} を {@code RULE_VIOLATION} に変えれば、
     * その経路だけが 422 を返すようになる。
     * `check-error-codes.py` は型の一覧を突き合わせるが、
     * <strong>同じ型に別の HTTP が割り当たったことは検出できない</strong>（落とし穴 124）。
     *
     * <p>利用者への説明（{@code title} と本文）は操作ごとに違ってよいので、
     * 静的ファクトリで分ける。<strong>2 つの {@code String} を並べた
     * コンストラクタは公開しない</strong> — 入れ替えてもコンパイルが通り、
     * 見出しと本文が入れ替わるだけで画面は動き続ける（落とし穴 187）。
     */
    final class MonthAlreadyClosedException extends DomainException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final String title;

        private MonthAlreadyClosedException(String detail, String title) {
            super(detail);
            this.title = title;
        }

        /** 意思表示（訂正申請・年休）を締め済みの月へ出した。 */
        static MonthAlreadyClosedException of(YearMonth month) {
            return new MonthAlreadyClosedException(
                    "締め済みの月は変更できません: " + month, "締め済みの月です");
        }

        /** 締め済みの月の勤怠を計算し直そうとした。 */
        public static MonthAlreadyClosedException recalculating(YearMonth month) {
            return new MonthAlreadyClosedException(
                    "締め済みの月は再計算できません: " + month, "締め済みの月です");
        }

        /**
         * 全社で共有する表（会社カレンダー・就業規則）を変えようとした。
         *
         * @param subject 変更しようとした対象（「会社カレンダー」など）
         */
        public static MonthAlreadyClosedException affecting(YearMonth month, String subject) {
            return new MonthAlreadyClosedException(
                    "締め済みの月に影響するため%sを変更できません: %s".formatted(subject, month),
                    "締め済みの月に影響する変更はできません");
        }

        /**
         * 締め済みの月へ遡る変更（異動・退職・部署長の任命など）。
         *
         * @param operation 行おうとした操作（「異動」など）
         */
        public static MonthAlreadyClosedException goingBackTo(YearMonth month,
                                                              String operation) {
            return new MonthAlreadyClosedException(
                    "締め済みの月に遡るため%sできません: %s".formatted(operation, month),
                    "締め済みの月に遡る変更はできません");
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
            return title;
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
