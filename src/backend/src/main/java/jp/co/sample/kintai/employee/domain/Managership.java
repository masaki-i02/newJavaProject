package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 部署長。<strong>有効期間を持つ。</strong>
 *
 * <p>1 つの部署に同時に 2 人の長はいない（DB の {@code managerships_no_overlap}）。
 * 一方で<strong>部署長の兼任は認める</strong>ので、
 * 1 人が複数の部署の長を務めることに制約は置かない（BR-11 補足）。
 */
public record Managership(DepartmentId departmentId, EmployeeId employeeId, DateRange period) {

    public Managership {
        if (departmentId == null || employeeId == null || period == null) {
            throw new IllegalArgumentException("部署長の項目に null は許されません");
        }
    }

    public static Managership startingAt(DepartmentId departmentId, EmployeeId employeeId,
                                         LocalDate from) {
        return new Managership(departmentId, employeeId, DateRange.startingAt(from));
    }

    public boolean covers(LocalDate date) {
        return period.contains(date);
    }

    public Managership closedAt(LocalDate toExclusive) {
        return new Managership(departmentId, employeeId,
                new DateRange(period.from(), toExclusive));
    }
}
