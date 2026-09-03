package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 就業規則の系列と、社員への適用のポート。 */
public interface WorkRuleSeriesRepository {

    Optional<WorkRuleSeries> findById(WorkRuleSeriesId id);

    List<WorkRuleSeries> findAll();

    void save(WorkRuleSeries series);

    /**
     * 社員に系列を適用する。
     *
     * <p><strong>指すのは版ではなく系列である。</strong>
     * 版を直接指すと、改定した瞬間に指し先が「過去の版」になり、
     * 全社員の勤怠計算が停止する（ADR 0003）。
     */
    void assign(EmployeeId employeeId, WorkRuleSeriesId seriesId, LocalDate validFrom);

    List<WorkRuleAssignment> findAssignments(EmployeeId employeeId);

    /**
     * 指定日に規則が適用されていない在籍者。
     *
     * <p>「在籍者全員に規則が適用されている」ことは DB では守れない。
     * 画面で検知するために置く。
     */
    List<EmployeeId> findEmployeesWithoutRuleOn(LocalDate date);
}
