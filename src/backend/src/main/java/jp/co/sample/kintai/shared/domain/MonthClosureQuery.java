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

    /** 打刻・再計算・カレンダーの変更などを受け付けてよい状態か。 */
    boolean acceptsChanges(EmployeeId employeeId, YearMonth month);
}
