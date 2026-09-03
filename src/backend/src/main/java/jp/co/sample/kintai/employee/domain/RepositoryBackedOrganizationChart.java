package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * ポートを組み合わせた {@link OrganizationChart} の既定実装。
 *
 * <p>フレームワークに依存しないので {@code domain} に置く。
 * Bean の定義は {@code employee.infrastructure} が行う（AR-01）。
 */
public final class RepositoryBackedOrganizationChart implements OrganizationChart {

    private final EmployeeRepository employees;
    private final DepartmentRepository departments;
    private final AssignmentRepository assignments;
    private final ManagershipRepository managerships;

    public RepositoryBackedOrganizationChart(EmployeeRepository employees,
                                             DepartmentRepository departments,
                                             AssignmentRepository assignments,
                                             ManagershipRepository managerships) {
        if (employees == null || departments == null
                || assignments == null || managerships == null) {
            throw new IllegalArgumentException("組織図のポートに null は許されません");
        }
        this.employees = employees;
        this.departments = departments;
        this.assignments = assignments;
        this.managerships = managerships;
    }

    @Override
    public Optional<Department> departmentOf(EmployeeId employeeId, LocalDate date) {
        return assignments.findEffective(employeeId, date)
                .flatMap(assignment -> departments.findById(assignment.departmentId()));
    }

    @Override
    public List<Department> selfAndAncestorsOf(DepartmentId departmentId) {
        return departments.findSelfAndAncestors(departmentId);
    }

    @Override
    public Optional<Managership> managerOf(DepartmentId departmentId, LocalDate date) {
        return managerships.findEffective(departmentId, date);
    }

    /**
     * その月の途中で所属が始まっていれば、その開始日。
     *
     * <p>月初日に所属が始まっている場合は<strong>空を返す</strong>。
     * 「月の途中で始まった」ではないので、基準日は通常どおり月初日でよい。
     */
    @Override
    public Optional<LocalDate> assignmentStartWithin(EmployeeId employeeId, YearMonth month) {
        DateRange target = new DateRange(month.atDay(1), month.plusMonths(1).atDay(1));
        return assignments.findHistory(employeeId).stream()
                .map(assignment -> assignment.period().from())
                .filter(target::contains)
                .filter(start -> start.isAfter(month.atDay(1)))
                .min(LocalDate::compareTo);
    }

    @Override
    public boolean isActiveOn(EmployeeId employeeId, LocalDate date) {
        return employees.findById(employeeId)
                .map(employee -> employee.isActiveOn(date))
                .orElse(false);
    }
}
