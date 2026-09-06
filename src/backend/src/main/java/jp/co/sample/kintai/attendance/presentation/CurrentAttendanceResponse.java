package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jp.co.sample.kintai.attendance.application.TimeClockService.CurrentAttendance;

/**
 * 打刻画面が「次に押せるボタン」を決めるための現在の勤務状態
 * （[03 API設計書 2.2]）。
 *
 * <p><strong>{@code availableActions} をサーバが返す。</strong>
 * 状態機械（BR-02）はドメインの知識であり、画面に複製すると
 * 仕様変更のたびに 2 か所を直すことになる。ボタンの活性制御はこの配列に従う。
 *
 * <p><strong>{@code unclosedWorkDates} が空でなくても打刻ボタンは消さない。</strong>
 * 別の日の不整合で、その日の労働の記録を止めてはいけない（落とし穴 19・68）。
 *
 * @param workDate          いま追記の対象になっている勤務日
 * @param status            状態機械の状態。<strong>打刻の並びが壊れていれば {@code null}</strong>
 * @param availableActions  次に打てる打刻。<strong>退勤済なら空</strong>
 * @param punches           その勤務日の打刻（識別子つき）
 * @param unclosedWorkDates 退勤を打ち忘れた過去の勤務日（BR-03）
 */
public record CurrentAttendanceResponse(LocalDate workDate, String status,
                                        List<String> availableActions,
                                        List<Punch> punches,
                                        List<LocalDate> unclosedWorkDates) {

    /**
     * その勤務日の打刻の 1 件。
     *
     * <p><strong>いま有効なものだけを返す。</strong>
     * 取り消された打刻まで並べると、打刻画面が「いま何回押したか」を表せなくなる。
     * 証跡は訂正申請の画面が {@code GET .../time-clocks} で引く（BR-09）。
     */
    public record Punch(String id, String type, LocalDateTime occurredAt) {
    }

    static CurrentAttendanceResponse from(CurrentAttendance current) {
        return new CurrentAttendanceResponse(current.workDate(),
                current.status() == null ? null : current.status().name(),
                current.availableActions().stream().map(Enum::name).toList(),
                current.punches().stream()
                        .map(punch -> new Punch(punch.id().value().toString(),
                                punch.event().type().name(), punch.event().occurredAt()))
                        .toList(),
                current.unclosedWorkDates());
    }
}
