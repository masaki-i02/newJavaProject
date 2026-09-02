package jp.co.sample.kintai.attendance.domain;

import java.time.LocalDateTime;

/**
 * 打刻イベント（BR-02）。
 *
 * <p><strong>種別を enum のフィールドではなく型で表現する。</strong>
 * 1 つのクラスに {@code EventType} を持たせると、状態遷移を書く {@code switch} に
 * {@code default} 句が必要になり、種別を増やしたときのハンドリング漏れが実行時まで分からない。
 */
public sealed interface TimeClockEvent
        permits TimeClockEvent.ClockIn, TimeClockEvent.ClockOut,
                TimeClockEvent.BreakStart, TimeClockEvent.BreakEnd {

    /** 打刻時刻。<strong>秒を含む。</strong> 分へそろえるのは区間へ変換するときだけ（BR-01）。 */
    LocalDateTime occurredAt();

    /** 出勤。 */
    record ClockIn(LocalDateTime occurredAt) implements TimeClockEvent {
    }

    /** 退勤。 */
    record ClockOut(LocalDateTime occurredAt) implements TimeClockEvent {
    }

    /** 休憩開始。 */
    record BreakStart(LocalDateTime occurredAt) implements TimeClockEvent {
    }

    /** 休憩終了。 */
    record BreakEnd(LocalDateTime occurredAt) implements TimeClockEvent {
    }
}
