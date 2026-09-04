package jp.co.sample.kintai.shared.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * 承認済みの年休の取得日を問い合わせるポート（BR-16）。
 *
 * <p>年休を持つのは {@code leave} だが、それを知りたいのは
 * <strong>所定総労働時間を数える {@code attendance}</strong> である。
 * 素直に問い合わせると依存が循環するので、
 * ポートを {@code shared} に置き、実装を {@code leave} に置く（ADR 0004）。
 * {@code MonthClosureQuery} と同じ形である。
 *
 * <p><strong>返すのは日付だけである。</strong>
 * 申請や付与といった {@code leave} が所有する概念を {@code shared} に持ち込まない（AR-10）。
 */
public interface PaidLeaveDays {

    /**
     * その期間に取得した、承認済みの年休の日。
     *
     * @param period <strong>清算期間（暦月 ∩ 在籍期間）</strong>を渡す。暦月ではない
     */
    Set<LocalDate> approvedOn(EmployeeId employeeId, DateRange period);
}
