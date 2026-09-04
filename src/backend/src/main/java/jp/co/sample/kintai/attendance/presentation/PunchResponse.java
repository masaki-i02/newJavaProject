package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jp.co.sample.kintai.attendance.application.PunchResult;

/**
 * 打刻の応答。
 *
 * <p><strong>勤務日を必ず返す。</strong>
 * 日をまたぐ勤務では打刻した日と勤務日が一致しない（BR-03）ので、
 * 利用者に「これは前日の勤務です」と示す必要がある。
 *
 * <p>退勤前は {@code attendance} を返さない。
 * 勤務が完了していない時点の集計値は意味を持たない。
 *
 * @param calculationStatus  日次計算の結果。
 *                           <strong>打刻の成否とは別である。</strong>
 *                           就業規則が無くても打刻は記録される
 * @param unclosedWorkDates  まだ退勤していない過去の勤務日（BR-03）。
 *                           <strong>警告であって、打刻を拒む理由ではない。</strong>
 *                           画面はこれを示して訂正申請へ誘導し、打刻ボタンは消さない
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PunchResponse(LocalDate workDate, String calculationStatus,
                            DailyAttendanceResponse attendance,
                            List<LocalDate> unclosedWorkDates) {

    public static PunchResponse from(PunchResult result) {
        return new PunchResponse(
                result.workDate(),
                result.status().name(),
                result.attendance().map(DailyAttendanceResponse::from).orElse(null),
                result.unclosedWorkDates());
    }
}
