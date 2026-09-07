package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.WorkRuleAssignment;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesUsage;

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

    @Override
    public List<WorkRuleSeriesUsage> findUsagesIn(DateRange period) {
        // ★ 同じ系列に複数の適用行があるのは普通のこと（社員ごと・異動ごと）なので、
        //   同じ (系列, 期間) はまとめる。日ごとの判定では区別できない
        return assignments.findAssignmentsIn(period.from(), period.toExclusive()).stream()
                .map(row -> new WorkRuleSeriesUsage(
                        new WorkRuleSeriesId(row.getWorkRuleSeriesId()),
                        WorkRuleMapper.toRange(row.getValidFrom(), row.getValidTo())))
                .distinct()
                .toList();
    }

    /**
     * 版を 1 つ進める。
     *
     * <p><strong>SQL の側で条件つきに進める。</strong>
     * 読んでから書くと、その隙間に別の要求が入る。
     * 更新できた行数が 0 なら、誰かが先に改定している。
     */
    @Override
    public boolean bumpVersion(WorkRuleSeriesId id, long expectedVersion) {
        return series.bumpVersion(id.value(), expectedVersion) == 1;
    }

    private static WorkRuleSeries toDomain(WorkRuleSeriesEntity entity) {
        return new WorkRuleSeries(new WorkRuleSeriesId(entity.getId()), entity.getName(),
                WorkRuleMapper.toOptional(entity.getAbolishedOn()), entity.getVersion());
    }
}
