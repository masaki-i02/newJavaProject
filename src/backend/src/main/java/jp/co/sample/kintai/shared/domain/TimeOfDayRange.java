package jp.co.sample.kintai.shared.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 壁掛け時計時刻の範囲 {@code [start, end)}。
 *
 * <p>日付を持たないので、日をまたぐ範囲は {@code start > end} で表す（22:00–05:00 など）。
 * 素朴に扱うと「22:00 は 05:00 より後だから範囲が作れない」という不具合の温床になるため、
 * その扱いをこの型に閉じ込める。
 */
public record TimeOfDayRange(LocalTime start, LocalTime end) {

    public TimeOfDayRange {
        if (start == null || end == null) {
            throw new IllegalArgumentException("時刻の範囲に null は許されません");
        }
        if (start.equals(end)) {
            throw new IllegalArgumentException("時刻の範囲の長さを 0 にはできません: " + start);
        }
    }

    /** 日をまたぐか。 */
    public boolean crossesMidnight() {
        return start.isAfter(end);
    }

    public Duration duration() {
        Duration span = Duration.between(start, end);
        return crossesMidnight() ? span.plusDays(1) : span;
    }

    /** この範囲が {@code other} を完全に含むか。日をまたぐ範囲どうしは比較しない。 */
    public boolean contains(TimeOfDayRange other) {
        if (crossesMidnight() || other.crossesMidnight()) {
            throw new IllegalArgumentException(
                    "日をまたぐ範囲の包含は判定しません: %s / %s".formatted(this, other));
        }
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    /**
     * 指定した日付に重ねて、実際の日時の区間にする。
     *
     * <p>日をまたぐ範囲なら終了は翌日になる。
     */
    public TimeRange on(LocalDate date) {
        return new TimeRange(date.atTime(start),
                crossesMidnight() ? date.plusDays(1).atTime(end) : date.atTime(end));
    }

    @Override
    public String toString() {
        return "%s–%s".formatted(start, end);
    }
}
