package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;

/**
 * どの付与から 1 日を消化したか（BR-15）。
 *
 * <p><strong>配分は行として残す。</strong> 都度導出すると、
 * 再判定で過去に付与が増えた瞬間に、承認済みの取得日の配分先が入れ替わる。
 * どの付与がいつ失効したかも変わるので、過去の残日数の説明がつかなくなる（ADR 0006）。
 */
public record LeaveAllocation(PaidLeaveGrantId grantId, LocalDate leaveDate) {

    public LeaveAllocation {
        if (grantId == null || leaveDate == null) {
            throw new IllegalArgumentException("配分の項目に null は許されません");
        }
    }
}
