package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 年休の申請のポート。 */
public interface PaidLeaveRequestRepository {

    void save(PaidLeaveRequest request);

    /** 状態を変える。版が一致しなければ楽観ロック違反。 */
    void update(PaidLeaveRequest request, long expectedVersion);

    Optional<PaidLeaveRequest> find(PaidLeaveRequestId id);

    /** その社員の申請をすべて読む。残日数の計算に使う。 */
    List<PaidLeaveRequest> findByEmployee(EmployeeId employeeId);

    /** その期間の承認済みの取得日。{@code PaidLeaveDays} の実装が使う。 */
    List<LocalDate> findApprovedDates(EmployeeId employeeId, DateRange period);

    /**
     * 承認待ちの一覧。
     *
     * <p><strong>閲覧範囲では絞らない。</strong>
     * 「配下部署か」は組織と基準日に依存するので、SQL に写すと
     * 組織の解決を 2 か所に持つことになる。絞るのは {@code application} 層である。
     */
    List<PaidLeaveRequest> findPending();

    /** 証跡を追記する。 */
    void appendEvent(LeaveRequestEvent event);
}
