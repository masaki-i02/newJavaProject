package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
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

    /**
     * 期間に<strong>実際に適用されている</strong>系列（BR-18）。
     *
     * <p>割増賃金の基礎額の分母（労基則 19 条 1 項 4 号）は、
     * その年度に使われている就業規則から導く。
     * {@link #findAll()} で全系列を舐めると、<strong>廃止済みの系列や過去の版まで巻き込み</strong>、
     * 一度でも違う所定の規則を作ったことがある会社は永久に出力できなくなる。
     */
    List<WorkRuleSeriesId> findSeriesIdsInUse(DateRange period);
}
