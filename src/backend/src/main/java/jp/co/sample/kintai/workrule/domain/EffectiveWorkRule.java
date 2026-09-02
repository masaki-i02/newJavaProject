package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 指定日に社員へ適用される就業規則を決める（時点解決）。
 *
 * <p>解決は 2 段階である。
 * <pre>
 * employeeId + date ──> 適用（assignment）──> 系列
 *                                              │ + date
 *                                              ▼
 *                                          版（WorkRule）
 * </pre>
 *
 * <p>版を直接指していると、改定した瞬間に指し先が「過去の版」になり、
 * <strong>全社員の勤怠計算が停止する</strong>（ADR 0003）。
 */
public final class EffectiveWorkRule {

    private EffectiveWorkRule() {
    }

    /**
     * 指定日に有効な版を返す。
     *
     * @param assignments その社員の適用の履歴
     * @param versions    その系列の版の履歴
     * @param date        対象日
     * @return 適用が無い、または版に隙間がある場合は空。<strong>例外にしない</strong>
     */
    public static Optional<WorkRule> resolve(List<WorkRuleAssignment> assignments,
                                             List<WorkRule> versions, LocalDate date) {
        return assignments.stream()
                .filter(assignment -> assignment.period().contains(date))
                .findFirst()
                .flatMap(assignment -> versions.stream()
                        .filter(version -> version.seriesId().equals(assignment.seriesId()))
                        .filter(version -> version.validPeriod().contains(date))
                        .findFirst());
    }
}
