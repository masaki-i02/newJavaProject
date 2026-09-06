package jp.co.sample.kintai.leave.infrastructure;

import java.time.LocalDate;
import java.util.Set;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.leave.domain.PaidLeaveRequestRepository;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.PaidLeaveDays;

/**
 * {@link PaidLeaveDays} の実装。
 *
 * <p>年休を持つ {@code leave} が提供する（ADR 0004）。
 * ポート越しに答えるので、{@code attendance} は申請や付与の構造を知らずに済む。
 * {@code MonthClosureQuery} と同じ形である。
 */
@Repository
class PaidLeaveDaysAdapter implements PaidLeaveDays {

    private final PaidLeaveRequestRepository requests;

    PaidLeaveDaysAdapter(PaidLeaveRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public Set<LocalDate> approvedOn(EmployeeId employeeId, DateRange period) {
        return Set.copyOf(requests.findApprovedDates(employeeId, period));
    }
}
