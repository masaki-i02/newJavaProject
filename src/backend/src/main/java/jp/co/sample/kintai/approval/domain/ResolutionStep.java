package jp.co.sample.kintai.approval.domain;

import jp.co.sample.kintai.employee.domain.Department;

/** 承認者を遡って探した経路の 1 段。その部署でなぜ決まらなかったか（決まったなら NONE）。 */
public record ResolutionStep(Department department, SkipReason reason) {

    public ResolutionStep {
        if (department == null || reason == null) {
            throw new IllegalArgumentException("解決経路の項目に null は許されません");
        }
    }
}
