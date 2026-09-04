package jp.co.sample.kintai.approval.domain;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 月次勤怠のポート。 */
public interface MonthlyAttendanceRepository {

    Optional<MonthlyAttendance> find(EmployeeId employeeId, YearMonth month);

    /**
     * 登録または更新する。
     *
     * <p><strong>版を突き合わせない経路。</strong>
     * 訂正の承認による自動差戻しのように、
     * 画面の値を見て誰かが決めたわけではない遷移で使う。
     */
    void save(MonthlyAttendance attendance);

    /**
     * 版が一致するときだけ登録または更新する。
     *
     * <p><strong>利用者が画面から起こす遷移で使う</strong>（API設計書 1.1）。
     * 承認者が「この内容でよい」と判断してから承認するまでの間に、
     * 訂正の承認などで内容が変わっていることがある。
     * 突き合わせないと、<strong>承認者が見ていない内容を承認済みにしてしまう。</strong>
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException 版が一致しない場合
     */
    void save(MonthlyAttendance attendance, long expectedVersion);

    /** 現在の版。<strong>まだ行が無い月は 0。</strong> 提出が最初の遷移になる。 */
    long currentVersion(EmployeeId employeeId, YearMonth month);

    /**
     * その月に提出済みの月次勤怠。<strong>承認待ち一覧に使う。</strong>
     *
     * <p>絞り込み（配下部署の社員だけ）は行わない。
     * <strong>誰が見てよいかは組織に依存する業務判断</strong>であり、
     * {@code application} 層が {@code EmployeeVisibility} で判定する。
     */
    List<MonthlyAttendance> findSubmitted(YearMonth month);
}
