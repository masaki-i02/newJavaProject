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

    /**
     * 打刻種別の<strong>入出力のための</strong>判別値。
     *
     * <p>API と DB は「文字列 1 つ」で種別を受け渡すので、
     * その対応づけをどこかに置く必要がある。ここに置けば、
     * 種別を増やしたときに {@code at} の {@code switch} がコンパイルエラーになる。
     *
     * <p><strong>状態遷移の分岐にこれを使わない。</strong>
     * 分岐は {@code sealed interface} に対する網羅性検査つき {@code switch} で書く。
     * この enum で分岐すると {@code default} 句が必要になり、
     * 種別を増やしたときのハンドリング漏れが実行時まで分からなくなる。
     */
    enum Type {
        CLOCK_IN, CLOCK_OUT, BREAK_START, BREAK_END;

        /** その種別の打刻を作る。 */
        public TimeClockEvent at(LocalDateTime occurredAt) {
            return switch (this) {
                case CLOCK_IN -> new ClockIn(occurredAt);
                case CLOCK_OUT -> new ClockOut(occurredAt);
                case BREAK_START -> new BreakStart(occurredAt);
                case BREAK_END -> new BreakEnd(occurredAt);
            };
        }
    }

    /** その打刻の種別。永続化と表示のためだけに使う。 */
    default Type type() {
        return switch (this) {
            case ClockIn ignored -> Type.CLOCK_IN;
            case ClockOut ignored -> Type.CLOCK_OUT;
            case BreakStart ignored -> Type.BREAK_START;
            case BreakEnd ignored -> Type.BREAK_END;
        };
    }
}
