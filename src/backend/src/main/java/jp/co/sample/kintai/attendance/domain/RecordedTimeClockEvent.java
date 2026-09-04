package jp.co.sample.kintai.attendance.domain;

/**
 * 保存済みの打刻。識別子つき（BR-09）。
 *
 * <p>訂正申請は「どの打刻を取り消すか」を指す必要があるので、
 * 計算に使う {@link TimeClockEvent} とは別に、識別子を添えた形を用意する。
 *
 * @param id    打刻の識別子
 * @param event 打刻そのもの
 */
public record RecordedTimeClockEvent(TimeClockEventId id, TimeClockEvent event) {

    public RecordedTimeClockEvent {
        if (id == null || event == null) {
            throw new IllegalArgumentException("保存済みの打刻の項目に null は許されません");
        }
    }
}
