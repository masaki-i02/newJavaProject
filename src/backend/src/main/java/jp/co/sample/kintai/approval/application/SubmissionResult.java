package jp.co.sample.kintai.approval.application;

import java.time.LocalDate;
import java.util.List;

import jp.co.sample.kintai.approval.domain.MonthlyAttendance;

/**
 * 提出の結果（BR-10 / BR-16）。
 *
 * <p><strong>提出を止めない。</strong> 一次証拠の記録も手続きも、
 * 不整合を理由に止めない（落とし穴 19）。気づかせるのは警告で行う。
 *
 * @param attendance              提出後の月次勤怠
 * @param workedOnPaidLeaveDates  <strong>承認済みの年休の日なのに実労働がある日。</strong>
 *                                取り消せるのは締め前だけなので、
 *                                締め前に必ず通る提出の応答に載せる（落とし穴 97）
 */
public record SubmissionResult(MonthlyAttendance attendance,
                               List<LocalDate> workedOnPaidLeaveDates) {

    public SubmissionResult {
        if (attendance == null || workedOnPaidLeaveDates == null) {
            throw new IllegalArgumentException("提出の結果に null は許されません");
        }
        workedOnPaidLeaveDates = List.copyOf(workedOnPaidLeaveDates);
    }
}
