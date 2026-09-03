package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link EmployeeRepository} の実装。ドメインの record と JPA エンティティを変換する。
 *
 * <p>変換をここに置くのは、<strong>ドメインを JPA の制約から切り離すため</strong>である
 * （ADR 0002）。エンティティはこのパッケージの外に出ない。
 */
@Repository
class EmployeeRepositoryAdapter implements EmployeeRepository {

    private final EmployeeJpaRepository jpa;

    EmployeeRepositoryAdapter(EmployeeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Employee> findById(EmployeeId id) {
        return jpa.findById(id.value()).map(EmployeeRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Employee> findByNumber(EmployeeNumber number) {
        return jpa.findByEmployeeNumber(number.value())
                .map(EmployeeRepositoryAdapter::toDomain);
    }

    @Override
    public List<Employee> findAll(LocalDate asOf, boolean includeRetired) {
        List<EmployeeEntity> rows = includeRetired
                ? jpa.findAllByOrderByEmployeeNumber()
                : jpa.findActiveOn(asOf);
        return rows.stream().map(EmployeeRepositoryAdapter::toDomain).toList();
    }

    @Override
    public void save(Employee employee) {
        UUID id = employee.id().value();
        EmployeeEntity entity = jpa.findById(id).orElseGet(() -> new EmployeeEntity(id));
        entity.setEmployeeNumber(employee.number().value());
        entity.setName(employee.name());
        entity.setEmail(employee.email().value());
        entity.setHiredOn(employee.hiredOn());
        // ★ 番兵ではなく Optional なので、そのまま null へ落とす
        entity.setRetiredOn(employee.retiredOn().orElse(null));
        entity.setRoles(employee.roles());
        jpa.save(entity);
    }

    private static Employee toDomain(EmployeeEntity entity) {
        return new Employee(
                new EmployeeId(entity.getId()),
                new EmployeeNumber(entity.getEmployeeNumber()),
                entity.getName(),
                new Email(entity.getEmail()),
                entity.getHiredOn(),
                Periods.toOptional(entity.getRetiredOn()),
                entity.getRoles());
    }
}
