package jp.co.sample.kintai.leave.application;

import java.time.LocalDate;
import java.util.Optional;

import jp.co.sample.kintai.approval.domain.AttendanceState;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;

/**
 * 年休の決裁・取消の結果（BR-16）。
 *
 * <p><strong>月次勤怠の状態を含める。</strong>
 * 承認・取消は提出済みの月を下書きへ戻す（{@code REVERT_BY_LEAVE}）ので、
 * 伝えないと再提出が忘れられる（訂正の承認と同じ形）。
 *
 * @param version          更新後の版。<strong>手元の集約が持つ版ではない</strong>
 * @param allocatedGrantedOn どの付与から消化したかの<strong>付与日</strong>。
 *                           識別子ではなく日付を返すのは、失効時期に直結するからである
 */
public record LeaveDecisionResult(PaidLeaveRequest request, long version,
                                  Optional<LocalDate> allocatedGrantedOn,
                                  AttendanceState monthlyAttendanceState) {

    public LeaveDecisionResult {
        if (request == null || allocatedGrantedOn == null
                || monthlyAttendanceState == null) {
            throw new IllegalArgumentException("決裁の結果に null は許されません");
        }
    }
}
