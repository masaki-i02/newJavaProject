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

    DepartmentRepositoryAdapter(DepartmentJpaRepository jpa) {
        this.jpa = jpa;
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
