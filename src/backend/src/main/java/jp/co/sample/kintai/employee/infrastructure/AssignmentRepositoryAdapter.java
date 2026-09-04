package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.employee.domain.Assignment;
import jp.co.sample.kintai.employee.domain.AssignmentRepository;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** {@link AssignmentRepository} の実装。無期限は番兵 ⇄ {@code NULL} で写す（{@link Periods}）。 */
@Repository
class AssignmentRepositoryAdapter implements AssignmentRepository {

    private final AssignmentJpaRepository jpa;

    AssignmentRepositoryAdapter(AssignmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Assignment> findEffective(EmployeeId employeeId, LocalDate date) {
        return jpa.findEffective(employeeId.value(), date)
                .map(AssignmentRepositoryAdapter::toDomain);
    }

    @Override
    public List<Assignment> findHistory(EmployeeId employeeId) {
        return jpa.findByEmployeeIdOrderByValidFrom(employeeId.value()).stream()
                .map(AssignmentRepositoryAdapter::toDomain).toList();
    }

    /**
     * 所属を 1 件保存する。
     *
     * <p>同じ社員・同じ開始日の行を上書きする。
     * 識別子はドメインが持たない（所属は社員と期間で一意に決まる）ので、
     * <strong>キーは {@code (employeeId, validFrom)}</strong> とみなす。
     */
    @Override
    public void save(Assignment assignment) {
        AssignmentEntity entity = jpa
                .findByEmployeeIdOrderByValidFrom(assignment.employeeId().value()).stream()
                .filter(row -> row.getValidFrom().equals(assignment.period().from()))
                .findFirst()
                .orElseGet(() -> new AssignmentEntity(UUID.randomUUID()));
        entity.setEmployeeId(assignment.employeeId().value());
        entity.setDepartmentId(assignment.departmentId().value());
        entity.setValidFrom(assignment.period().from());
        entity.setValidTo(Periods.toColumn(assignment.period()));
        jpa.save(entity);
    }

    /**
     * 現在開いている期間を閉じる。
     *
     * <p><strong>複数開いていたら例外にする。</strong> 兼務は扱わないので、
     * ある社員に開いた所属が 2 つあるのは DB の {@code assignments_no_overlap} が
     * 壊れた証拠である。黙って 1 つだけ閉じると、残りが無期限のまま残る。
     */
    @Override
    public void close(EmployeeId employeeId, LocalDate toExclusive) {
        List<AssignmentEntity> open = jpa.findOpen(employeeId.value());
        if (open.size() > 1) {
            throw new IllegalStateException(
                    "開いている所属が %d 件あります（兼務は扱わない）: %s"
                            .formatted(open.size(), employeeId));
        }
        open.forEach(entity -> {
            entity.setValidTo(toExclusive);
            jpa.save(entity);
        });
    }

    @Override
    public int reopenClosedAt(EmployeeId employeeId, LocalDate toExclusive) {
        List<AssignmentEntity> closed = jpa.findClosedAt(employeeId.value(), toExclusive);
        closed.forEach(entity -> {
            entity.setValidTo(null);
            jpa.save(entity);
        });
        return closed.size();
    }

    private static Assignment toDomain(AssignmentEntity entity) {
        return new Assignment(
                new EmployeeId(entity.getEmployeeId()),
                new DepartmentId(entity.getDepartmentId()),
                Periods.toRange(entity.getValidFrom(), entity.getValidTo()));
    }
}
