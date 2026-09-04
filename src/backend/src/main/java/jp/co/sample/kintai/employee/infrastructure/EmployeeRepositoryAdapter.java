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

    /**
     * 版を突き合わせてから保存する。
     *
     * <p>読んでから比べる形にしている。{@code @Version} による自動検出は
     * 「読み込んだ版」と比べるので、<strong>利用者が画面で見ていた版とは比べてくれない。</strong>
     */
    @Override
    public List<Employee> findForDirectory(LocalDate asOf, boolean includeRetired) {
        List<EmployeeEntity> rows = includeRetired
                ? jpa.findAllByOrderByEmployeeNumber()
                : jpa.findNotRetiredOn(asOf);
        return rows.stream().map(EmployeeRepositoryAdapter::toDomain).toList();
    }

    @Override
    public void save(Employee employee, long expectedVersion) {
        long actual = currentVersion(employee.id());
        if (actual != expectedVersion) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "社員の版が一致しません: 期待 %d / 現在 %d"
                            .formatted(expectedVersion, actual));
        }
        save(employee);
    }

    @Override
    public long currentVersion(EmployeeId id) {
        return jpa.findById(id.value()).map(EmployeeEntity::getVersion).orElse(0L);
    }

    @Override
    public boolean existsActiveNumber(EmployeeNumber number) {
        return jpa.findByEmployeeNumber(number.value())
                .filter(row -> row.getRetiredOn() == null).isPresent();
    }

    /**
     * メールアドレスが在籍者と重複するか。
     *
     * <p><strong>ここで大文字小文字を畳まない。</strong>
     * {@link Email} が生成の時点で小文字へ正規化しており、保存される値も正規化済みである。
     * ここで {@code equalsIgnoreCase} を使うと同じ規則が 2 か所に現れ、
     * どちらが正なのか決められなくなる。
     */
    @Override
    public boolean existsActiveEmail(Email email) {
        return jpa.findAllByOrderByEmployeeNumber().stream()
                .filter(row -> row.getRetiredOn() == null)
                .anyMatch(row -> row.getEmail().equals(email.value()));
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
