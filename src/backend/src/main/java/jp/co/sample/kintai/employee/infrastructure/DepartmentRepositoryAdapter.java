package jp.co.sample.kintai.employee.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentCode;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;

/** {@link DepartmentRepository} の実装。 */
@Repository
class DepartmentRepositoryAdapter implements DepartmentRepository {

    private final DepartmentJpaRepository jpa;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    DepartmentRepositoryAdapter(DepartmentJpaRepository jpa,
                                org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Department> findById(DepartmentId id) {
        return jpa.findById(id.value()).map(DepartmentRepositoryAdapter::toDomain);
    }

    @Override
    public List<Department> findAll() {
        return jpa.findAllByOrderByCode().stream()
                .map(DepartmentRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<Department> findSelfAndAncestors(DepartmentId departmentId) {
        return jpa.findSelfAndAncestors(departmentId.value()).stream()
                .map(DepartmentRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<Department> findSelfAndDescendants(DepartmentId departmentId) {
        return jpa.findSelfAndDescendants(departmentId.value()).stream()
                .map(DepartmentRepositoryAdapter::toDomain).toList();
    }

    @Override
    public boolean existsActiveCode(DepartmentCode code) {
        return jpa.findAllByOrderByCode().stream()
                .filter(row -> row.getAbolishedOn() == null)
                .anyMatch(row -> row.getCode().equals(code.value()));
    }

    /**
     * 階層の変更を直列化する。
     *
     * <p><strong>{@code SHARE ROW EXCLUSIVE} を取る。</strong>
     * 同じロックどうしは互いに排他するが、通常の {@code SELECT} は妨げない。
     * 親の変更が走っている間も、組織図の参照は止まらない。
     *
     * <p>ロックはトランザクションの終了で自動的に解放される。
     */
    @Override
    public void lockForHierarchyChange() {
        jdbc.execute("LOCK TABLE departments IN SHARE ROW EXCLUSIVE MODE");
    }

    @Override
    public void save(Department department) {
        UUID id = department.id().value();
        DepartmentEntity entity = jpa.findById(id).orElseGet(() -> new DepartmentEntity(id));
        entity.setCode(department.code().value());
        entity.setName(department.name());
        entity.setParentId(department.parentId().map(DepartmentId::value).orElse(null));
        entity.setAbolishedOn(department.abolishedOn().orElse(null));
        jpa.save(entity);
    }

    private static Department toDomain(DepartmentEntity entity) {
        return new Department(
                new DepartmentId(entity.getId()),
                new DepartmentCode(entity.getCode()),
                entity.getName(),
                Optional.ofNullable(entity.getParentId()).map(DepartmentId::new),
                Periods.toOptional(entity.getAbolishedOn()));
    }
}
