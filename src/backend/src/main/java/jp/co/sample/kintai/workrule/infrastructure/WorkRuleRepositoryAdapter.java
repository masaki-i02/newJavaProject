package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.EffectiveWorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleAssignment;
import jp.co.sample.kintai.workrule.domain.WorkRuleId;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;

/**
 * {@link WorkRuleRepository} の実装。
 *
 * <p><strong>時点解決を SQL で書き直さない。</strong>
 * 「適用 → 系列 → 版」の 2 段解決は {@link EffectiveWorkRule}（ドメイン）が持っており、
 * 重複の検出もそこにある。SQL 側にもう 1 つ実装を置くと、
 * どちらが正しいかを 2 か所で保たなければならなくなる。
 * ここは<strong>行を読んでドメインへ渡すだけ</strong>にする。
 */
@Repository
class WorkRuleRepositoryAdapter implements WorkRuleRepository {

    private final WorkRuleJpaRepository rules;
    private final WorkRuleAssignmentJpaRepository assignments;

    WorkRuleRepositoryAdapter(WorkRuleJpaRepository rules,
                              WorkRuleAssignmentJpaRepository assignments) {
        this.rules = rules;
        this.assignments = assignments;
    }

    @Override
    public Optional<WorkRule> findEffective(EmployeeId employeeId, LocalDate date) {
        List<WorkRuleAssignment> applied = assignmentsOf(employeeId);
        return EffectiveWorkRule.resolve(employeeId, applied, versionsFor(applied), date);
    }

    /**
     * 期間分をまとめて解決する。
     *
     * <p>適用と版を<strong>1 度ずつ</strong>読み、あとはメモリ上で日ごとに引く。
     * 日ごとに問い合わせると、1 か月で 30 往復 × 2 テーブルになる。
     */
    @Override
    public Map<LocalDate, WorkRule> findEffectiveByPeriod(EmployeeId employeeId,
                                                          DateRange period) {
        List<WorkRuleAssignment> applied = assignmentsOf(employeeId);
        List<WorkRule> versions = versionsFor(applied);
        Map<LocalDate, WorkRule> resolved = new LinkedHashMap<>();
        for (LocalDate date = period.from(); date.isBefore(period.toExclusive());
                date = date.plusDays(1)) {
            LocalDate target = date;
            EffectiveWorkRule.resolve(employeeId, applied, versions, target)
                    .ifPresent(rule -> resolved.put(target, rule));
        }
        return Map.copyOf(resolved);
    }

    @Override
    public Optional<WorkRule> findById(WorkRuleId id) {
        return rules.findById(id.value()).map(WorkRuleMapper::toDomain);
    }

    @Override
    public List<WorkRule> findVersionsOf(WorkRuleSeriesId seriesId) {
        return rules.findBySeriesIdOrderByValidFrom(seriesId.value()).stream()
                .map(WorkRuleMapper::toDomain).toList();
    }

    @Override
    public void save(WorkRule workRule) {
        UUID id = workRule.id().value();
        WorkRuleEntity entity = rules.findById(id).orElseGet(() -> new WorkRuleEntity(id));
        WorkRuleMapper.apply(workRule, entity);
        rules.save(entity);
    }

    private List<WorkRuleAssignment> assignmentsOf(EmployeeId employeeId) {
        return assignments.findByEmployeeIdOrderByValidFrom(employeeId.value()).stream()
                .map(row -> new WorkRuleAssignment(
                        new EmployeeId(row.getEmployeeId()),
                        new WorkRuleSeriesId(row.getWorkRuleSeriesId()),
                        WorkRuleMapper.toRange(row.getValidFrom(), row.getValidTo())))
                .toList();
    }

    /** その社員が指す系列の版だけを読む。他人の系列を混ぜない。 */
    private List<WorkRule> versionsFor(List<WorkRuleAssignment> applied) {
        List<UUID> seriesIds = applied.stream()
                .map(assignment -> assignment.seriesId().value()).distinct().toList();
        if (seriesIds.isEmpty()) {
            return List.of();
        }
        return rules.findBySeriesIdInOrderByValidFrom(seriesIds).stream()
                .map(WorkRuleMapper::toDomain).toList();
    }
}
