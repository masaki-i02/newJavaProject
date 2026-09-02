package jp.co.sample.kintai.workrule.domain;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 社員への就業規則の適用。
 *
 * <p><strong>指すのは系列であって版ではない。</strong>
 * 版を指すと、改定した瞬間に全社員の規則が「未設定」になる（ADR 0003）。
 *
 * @param employeeId 社員
 * @param seriesId   就業規則の系列
 * @param period     適用期間。半開区間
 */
public record WorkRuleAssignment(EmployeeId employeeId, WorkRuleSeriesId seriesId,
                                 DateRange period) {

    public WorkRuleAssignment {
        if (employeeId == null || seriesId == null || period == null) {
            throw new IllegalArgumentException("就業規則の適用の項目に null は許されません");
        }
    }
}
