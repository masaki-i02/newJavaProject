package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.Managership;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** {@link ManagershipRepository} の実装。 */
@Repository
class ManagershipRepositoryAdapter implements ManagershipRepository {

    private final ManagershipJpaRepository jpa;

    ManagershipRepositoryAdapter(ManagershipJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Managership> findEffective(DepartmentId departmentId, LocalDate date) {
        return jpa.findEffective(departmentId.value(), date)
                .map(ManagershipRepositoryAdapter::toDomain);
    }

    @Override
    public List<Managership> findByManager(EmployeeId employeeId, LocalDate date) {
        return jpa.findByManager(employeeId.value(), date).stream()
                .map(ManagershipRepositoryAdapter::toDomain).toList();
    }

    @Override
    public void save(Managership managership) {
        ManagershipEntity entity = jpa.findOpen(managership.departmentId().value()).stream()
                .filter(row -> row.getValidFrom().equals(managership.period().from()))
                .findFirst()
                .orElseGet(() -> new ManagershipEntity(UUID.randomUUID()));
        entity.setDepartmentId(managership.departmentId().value());
        entity.setEmployeeId(managership.employeeId().value());
        entity.setValidFrom(managership.period().from());
        entity.setValidTo(Periods.toColumn(managership.period()));
        jpa.save(entity);
    }

    /**
     * 現任を閉じる。
     *
     * <p>1 部署に同時に 2 人の長はいないので、開いている行は高々 1 件である
     * （DB の {@code managerships_no_overlap}）。2 件あるのは制約が壊れた証拠。
     */
    @Override
    public void close(DepartmentId departmentId, LocalDate toExclusive) {
        List<ManagershipEntity> open = jpa.findOpen(departmentId.value());
        if (open.size() > 1) {
            throw new IllegalStateException(
                    "開いている部署長が %d 件あります: %s".formatted(open.size(), departmentId));
        }
        open.forEach(entity -> {
            entity.setValidTo(toExclusive);
            jpa.save(entity);
        });
    }

    @Override
    public int closeByManager(EmployeeId employeeId, LocalDate toExclusive) {
        List<ManagershipEntity> open = jpa.findOpenByManager(employeeId.value());
        open.forEach(entity -> {
            entity.setValidTo(toExclusive);
            jpa.save(entity);
        });
        return open.size();
    }

    @Override
    public int reopenClosedAt(EmployeeId employeeId, LocalDate toExclusive) {
        List<ManagershipEntity> closed = jpa.findClosedAt(employeeId.value(), toExclusive);
        closed.forEach(entity -> {
            entity.setValidTo(null);
            jpa.save(entity);
        });
        return closed.size();
    }

    private static Managership toDomain(ManagershipEntity entity) {
        return new Managership(
                new DepartmentId(entity.getDepartmentId()),
                new EmployeeId(entity.getEmployeeId()),
                Periods.toRange(entity.getValidFrom(), entity.getValidTo()));
    }
}
