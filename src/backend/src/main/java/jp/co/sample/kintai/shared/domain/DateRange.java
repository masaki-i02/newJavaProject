package jp.co.sample.kintai.shared.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 日付の<strong>半開区間</strong> {@code [from, toExclusive)}。
 *
 * <p>本システムの期間はすべてこの形で表す。閉区間を混ぜない。
 * 在籍期間（閉区間の感覚）と所属期間（半開区間）が混ざると、
 * <strong>退職日当日の 1 日が消える</strong>（CLAUDE.md 落とし穴 10）。
 *
 * <p>「最終日」を意味する値（退職日など）は {@code plusDays(1)} で上限へ変換する。
 *
 * @param from        開始日。含む
 * @param toExclusive 終了日。<strong>含まない。</strong> 無期限は {@link LocalDate#MAX}
 */
public record DateRange(LocalDate from, LocalDate toExclusive) {

    public DateRange {
        if (from == null || toExclusive == null) {
            throw new IllegalArgumentException("期間の端点に null は許されません");
        }
        if (!from.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "期間の開始は終了より前である必要があります: [%s, %s)".formatted(from, toExclusive));
        }
    }

    /** 上限のない期間。 */
    public static DateRange startingAt(LocalDate from) {
        return new DateRange(from, LocalDate.MAX);
    }

    /**
     * 最終日を含む形（閉区間）から作る。
     *
     * <p>退職日・廃止日のように「その日まで有効」という値は必ずここを通す。
     */
    public static DateRange closed(LocalDate from, LocalDate lastDay) {
        return new DateRange(from, lastDay.plusDays(1));
    }

    /** 最終日を含む形（閉区間）から作る。{@code lastDay} が空なら無期限。 */
    public static DateRange closed(LocalDate from, Optional<LocalDate> lastDay) {
        return lastDay.map(day -> closed(from, day)).orElseGet(() -> startingAt(from));
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && date.isBefore(toExclusive);
    }

    public boolean overlaps(DateRange other) {
        return from.isBefore(other.toExclusive) && other.from.isBefore(toExclusive);
    }

    /** 交差。重ならなければ空。 */
    public Optional<DateRange> intersect(DateRange other) {
        LocalDate start = from.isAfter(other.from) ? from : other.from;
        LocalDate end = toExclusive.isBefore(other.toExclusive) ? toExclusive : other.toExclusive;
        return start.isBefore(end) ? Optional.of(new DateRange(start, end)) : Optional.empty();
    }

    /** 暦日数。 */
    public long days() {
        return ChronoUnit.DAYS.between(from, toExclusive);
    }

    public boolean isUnbounded() {
        return LocalDate.MAX.equals(toExclusive);
    }
}
