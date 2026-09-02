package jp.co.sample.kintai.shared.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 日時の<strong>半開区間</strong> {@code [start, end)}。区間の切り貼りをこの型に閉じ込める。
 *
 * <p>端点は<strong>分精度に限る。</strong> 秒を含んだまま区間を細切れにすると、
 * 分割後の合計が分割前と一致しなくなり、
 * 「内訳の合計 = 実労働時間」という不変条件が壊れる（要件定義書 BR-01）。
 * 秒を分へそろえるのは打刻列を区間へ変換する 1 か所だけで行う。
 */
public record TimeRange(LocalDateTime start, LocalDateTime end) {

    public TimeRange {
        if (start == null || end == null) {
            throw new IllegalArgumentException("区間の端点に null は許されません");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException(
                    "区間の開始は終了より前である必要があります: [%s, %s)".formatted(start, end));
        }
        requireMinutePrecision(start, "開始");
        requireMinutePrecision(end, "終了");
    }

    private static void requireMinutePrecision(LocalDateTime instant, String label) {
        if (instant.getSecond() != 0 || instant.getNano() != 0) {
            throw new IllegalArgumentException(
                    "%sは分精度である必要があります: %s".formatted(label, instant));
        }
    }

    /** 労働の開始側。秒を切り捨て、時刻を早める（労働時間が長くなる側・BR-01）。 */
    public static LocalDateTime floorToMinute(LocalDateTime instant) {
        return instant.withSecond(0).withNano(0);
    }

    /** 労働の終了側。秒を切り上げ、時刻を遅らせる（労働時間が長くなる側・BR-01）。 */
    public static LocalDateTime ceilToMinute(LocalDateTime instant) {
        LocalDateTime floored = floorToMinute(instant);
        return floored.equals(instant) ? floored : floored.plusMinutes(1);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    public boolean contains(LocalDateTime instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    /** 交差。重ならなければ空。 */
    public Optional<TimeRange> intersect(TimeRange other) {
        LocalDateTime from = start.isAfter(other.start) ? start : other.start;
        LocalDateTime to = end.isBefore(other.end) ? end : other.end;
        return from.isBefore(to) ? Optional.of(new TimeRange(from, to)) : Optional.empty();
    }

    /**
     * 指定の時刻で 2 つに分ける。
     *
     * <p>端点や区間の外を指定した場合は分けない。<strong>長さ 0 の区間を作らない。</strong>
     */
    public List<TimeRange> splitAt(LocalDateTime instant) {
        if (!contains(instant) || instant.equals(start)) {
            return List.of(this);
        }
        return List.of(new TimeRange(start, instant), new TimeRange(instant, end));
    }
}
