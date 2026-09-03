package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 所属。<strong>有効期間を持つ。</strong>
 *
 * <p>所属と部署長の双方を履歴化する。片方だけだと
 * 「4 月分を 6 月に承認するとき、5 月に交代した新しい部署長が承認者になる」という誤りが起きる。
 *
 * <p>兼務は扱わない（1 社員の所属期間は重複しない）。
 * これは DB の {@code assignments_no_overlap} が物理的に保証する。
 */
public record Assignment(EmployeeId employeeId, DepartmentId departmentId, DateRange period) {

    public Assignment {
        if (employeeId == null || departmentId == null || period == null) {
            throw new IllegalArgumentException("所属の項目に null は許されません");
        }
    }

    /** 期限を定めない所属を開く。異動・退職のときに閉じる。 */
    public static Assignment startingAt(EmployeeId employeeId, DepartmentId departmentId,
                                        LocalDate from) {
        return new Assignment(employeeId, departmentId, DateRange.startingAt(from));
    }

    public boolean covers(LocalDate date) {
        return period.contains(date);
    }

    /** 指定日で閉じる。{@code toExclusive} はその日から所属しないことを表す。 */
    public Assignment closedAt(LocalDate toExclusive) {
        return new Assignment(employeeId, departmentId,
                new DateRange(period.from(), toExclusive));
    }
}
