package jp.co.sample.kintai.leave.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jp.co.sample.kintai.leave.domain.AnnualObligation;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 残日数の照会結果（BR-15 / BR-17）。
 *
 * @param remainingDays  基準日に有効な付与の残の合計。<strong>実体化した付与だけ</strong>
 * @param availableDays  未処理の申請を仮に配分したあと、なお残っている日数。
 *                       <strong>到来予定の付与を含む。</strong>
 *                       申請の受理判定と同じ仮配分から導く（落とし穴 96）
 * @param grants         付与の内訳。<strong>不付与の年も含める</strong>
 * @param remainingByGrant 付与ごとの残日数
 * @param obligations    年 5 日の取得義務の充足状況（BR-17）
 */
public record PaidLeaveSummary(EmployeeId employeeId, LocalDate asOf,
                               int remainingDays, int availableDays,
                               List<PaidLeaveGrant> grants,
                               Map<PaidLeaveGrantId, Integer> remainingByGrant,
                               List<AnnualObligation> obligations) {

    public PaidLeaveSummary {
        grants = List.copyOf(grants);
        remainingByGrant = Map.copyOf(remainingByGrant);
        obligations = List.copyOf(obligations);
    }

    /** その付与の残日数。不付与なら 0。 */
    public int remainingOf(PaidLeaveGrantId grantId) {
        return remainingByGrant.getOrDefault(grantId, 0);
    }
}
