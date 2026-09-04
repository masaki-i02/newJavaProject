package jp.co.sample.kintai.attendance.application;

import java.time.LocalDate;
import java.util.List;
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
 * @param attendance         計算できた場合の日次勤怠
 * @param unclosedWorkDates  <strong>まだ退勤していない過去の勤務日</strong>（BR-03）。
 *                           打刻を拒む理由にはしない。画面が訂正申請へ誘導するための警告である
 */
public record PunchResult(LocalDate workDate, CalculationStatus status,
                          Optional<DailyAttendance> attendance,
                          List<LocalDate> unclosedWorkDates) {

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
        if (workDate == null || status == null || attendance == null
                || unclosedWorkDates == null) {
            throw new IllegalArgumentException("打刻結果の項目に null は許されません");
        }
        // 今まさに打刻した日は「退勤し忘れ」ではない。含めると毎回警告が出る
        if (unclosedWorkDates.contains(workDate)) {
            throw new IllegalArgumentException(
                    "打刻した勤務日を未退勤の警告に含めません: " + workDate);
        }
        unclosedWorkDates = List.copyOf(unclosedWorkDates);
        if (status == CalculationStatus.CALCULATED && attendance.isEmpty()) {
            throw new IllegalArgumentException("計算できたのに日次勤怠がありません");
        }
        if (status != CalculationStatus.CALCULATED && attendance.isPresent()) {
            throw new IllegalArgumentException(
                    "計算していないのに日次勤怠があります: " + status);
        }
    }

    static PunchResult notClosed(LocalDate workDate, List<LocalDate> unclosed) {
        return new PunchResult(workDate, CalculationStatus.NOT_CLOSED, Optional.empty(),
                unclosed);
    }

    static PunchResult workRuleNotFound(LocalDate workDate, List<LocalDate> unclosed) {
        return new PunchResult(workDate, CalculationStatus.WORK_RULE_NOT_FOUND,
                Optional.empty(), unclosed);
    }

    static PunchResult calculated(LocalDate workDate, DailyAttendance attendance,
                                  List<LocalDate> unclosed) {
        return new PunchResult(workDate, CalculationStatus.CALCULATED,
                Optional.of(attendance), unclosed);
    }
}
