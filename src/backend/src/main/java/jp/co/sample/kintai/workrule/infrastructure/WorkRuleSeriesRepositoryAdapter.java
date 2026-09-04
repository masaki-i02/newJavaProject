package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.WorkRuleAssignment;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/** {@link WorkRuleSeriesRepository} の実装。 */
@Repository
class WorkRuleSeriesRepositoryAdapter implements WorkRuleSeriesRepository {

    private final WorkRuleSeriesJpaRepository series;
    private final WorkRuleAssignmentJpaRepository assignments;

    WorkRuleSeriesRepositoryAdapter(WorkRuleSeriesJpaRepository series,
                                    WorkRuleAssignmentJpaRepository assignments) {
        this.series = series;
        this.assignments = assignments;
    }

    @Override
    public Optional<WorkRuleSeries> findById(WorkRuleSeriesId id) {
        return series.findById(id.value()).map(WorkRuleSeriesRepositoryAdapter::toDomain);
    }

    @Override
    public List<WorkRuleSeries> findAll() {
        return series.findAllByOrderByName().stream()
                .map(WorkRuleSeriesRepositoryAdapter::toDomain).toList();
    }

    @Override
    public void save(WorkRuleSeries value) {
        UUID id = value.id().value();
        WorkRuleSeriesEntity entity = series.findById(id)
                .orElseGet(() -> new WorkRuleSeriesEntity(id));
        entity.setName(value.name());
        entity.setAbolishedOn(value.abolishedOn().orElse(null));
        series.save(entity);
    }

    /**
     * 適用する。
     *
     * <p><strong>現行の適用を閉じてから入れる。1 つの操作として行う。</strong>
     * 入れるだけにすると期間が重なり、
     * {@code work_rule_assignments_no_overlap} が拒否する。
     * つまり<strong>一度適用した社員の規則を二度と変更できなくなる。</strong>
     *
     * <p>閉じる位置は新しい適用の開始日。半開区間なので、
     * 同じ日から新しい規則が効く（隙間も重なりも生まれない）。
     */
    @Override
    public void assign(EmployeeId employeeId, WorkRuleSeriesId seriesId, LocalDate validFrom) {
        assignments.findByEmployeeIdOrderByValidFrom(employeeId.value()).stream()
                .filter(row -> row.getValidTo() == null || row.getValidTo().isAfter(validFrom))
                .filter(row -> !row.getValidFrom().isAfter(validFrom))
                .forEach(row -> {
                    row.setValidTo(validFrom);
                    assignments.save(row);
                });
        // 期間が長さ 0 になる適用（同じ日に 2 回適用した）は残さない
        assignments.findByEmployeeIdOrderByValidFrom(employeeId.value()).stream()
                .filter(row -> validFrom.equals(row.getValidFrom())
                        && validFrom.equals(row.getValidTo()))
                .forEach(assignments::delete);

        var entity = new WorkRuleAssignmentEntity(UUID.randomUUID());
        entity.setEmployeeId(employeeId.value());
        entity.setWorkRuleSeriesId(seriesId.value());
        entity.setValidFrom(validFrom);
        assignments.save(entity);
    }

    @Override
    public List<WorkRuleAssignment> findAssignments(EmployeeId employeeId) {
        return assignments.findByEmployeeIdOrderByValidFrom(employeeId.value()).stream()
                .map(row -> new WorkRuleAssignment(
                        new EmployeeId(row.getEmployeeId()),
                        new WorkRuleSeriesId(row.getWorkRuleSeriesId()),
                        WorkRuleMapper.toRange(row.getValidFrom(), row.getValidTo())))
                .toList();
    }

    @Override
    public List<EmployeeId> findEmployeesWithoutRuleOn(LocalDate date) {
        return assignments.findEmployeesWithoutRuleOn(date).stream()
                .map(EmployeeId::new).toList();
    }

    private static WorkRuleSeries toDomain(WorkRuleSeriesEntity entity) {
        return new WorkRuleSeries(new WorkRuleSeriesId(entity.getId()), entity.getName(),
                WorkRuleMapper.toOptional(entity.getAbolishedOn()));
    }
}
