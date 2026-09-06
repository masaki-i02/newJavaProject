package jp.co.sample.kintai.leave.application;

import jp.co.sample.kintai.leave.domain.AnnualObligation;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 年 5 日の取得義務の一覧の 1 行（BR-17）。
 *
 * <p><strong>社員番号・氏名・部署は持たない。</strong>
 * `employee` が所有する概念であり、画面はそちらから引く
 * （設計規約チェックリスト 3）。
 *
 * @param remainingDaysUntilDeadline 期限までの残り日数。
 *                                   <strong>半開区間の上限までの日数</strong>なので、
 *                                   期限日当日は 1 になる
 */
public record ObligationSummary(EmployeeId employeeId, AnnualObligation obligation,
                                int remainingDaysUntilDeadline) {

    public ObligationSummary {
        if (employeeId == null || obligation == null) {
            throw new IllegalArgumentException("取得義務の一覧の項目に null は許されません");
        }
    }
}
