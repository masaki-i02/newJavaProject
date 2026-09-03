package jp.co.sample.kintai.attendance.application;

import java.time.LocalDate;
import java.util.Optional;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;

/**
 * 打刻の結果。
 *
 * <p><strong>「打刻は成功したが計算はできなかった」を表現できる形にする。</strong>
 * 打刻の成否と計算の成否を 1 つの真偽値にまとめると、
 * 就業規則の未設定を理由に打刻を拒否する実装になりやすい。
 * それは働いた証拠を捨てることであり、してはいけない（CLAUDE.md 落とし穴 19）。
 *
 * @param workDate   その打刻が属する勤務日。<strong>打刻した暦日とは一致しない</strong>（BR-03）
 * @param status     計算の結果
 * @param attendance 計算できた場合の日次勤怠
 */
public record PunchResult(LocalDate workDate, CalculationStatus status,
                          Optional<DailyAttendance> attendance) {

    /** 日次計算がどうなったか。 */
    public enum CalculationStatus {

        /** 計算できた。 */
        CALCULATED,

        /** まだ退勤していない。異常ではない。 */
        NOT_CLOSED,

        /** その日に適用される就業規則が無い。<strong>打刻は記録されている。</strong> */
        WORK_RULE_NOT_FOUND
    }

    public PunchResult {
        if (workDate == null || status == null || attendance == null) {
            throw new IllegalArgumentException("打刻結果の項目に null は許されません");
        }
        if (status == CalculationStatus.CALCULATED && attendance.isEmpty()) {
            throw new IllegalArgumentException("計算できたのに日次勤怠がありません");
        }
        if (status != CalculationStatus.CALCULATED && attendance.isPresent()) {
            throw new IllegalArgumentException(
                    "計算していないのに日次勤怠があります: " + status);
        }
    }

    static PunchResult notClosed(LocalDate workDate) {
        return new PunchResult(workDate, CalculationStatus.NOT_CLOSED, Optional.empty());
    }

    static PunchResult workRuleNotFound(LocalDate workDate) {
        return new PunchResult(workDate, CalculationStatus.WORK_RULE_NOT_FOUND,
                Optional.empty());
    }

    static PunchResult calculated(LocalDate workDate, DailyAttendance attendance) {
        return new PunchResult(workDate, CalculationStatus.CALCULATED,
                Optional.of(attendance));
    }
}
