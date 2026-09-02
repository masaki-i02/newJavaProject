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
 * <p>上限・下限が無い期間は番兵（{@link #UNBOUNDED_END} / {@link #UNBOUNDED_START}）で表す。
 * <strong>番兵はそのままでは永続化できない。</strong>
 * PostgreSQL の {@code date} が表せる上限（5874897 年）を超えるため、
 * {@code infrastructure} 層が {@link #isUnbounded()} を見て SQL の {@code NULL} に写す。
 *
 * @param from        開始日。含む
 * @param toExclusive 終了日。<strong>含まない。</strong> 無期限は {@link #UNBOUNDED_END}
 */
public record DateRange(LocalDate from, LocalDate toExclusive) {

    /** 上限が無いことを表す番兵。DB では {@code NULL} に写す。 */
    public static final LocalDate UNBOUNDED_END = LocalDate.MAX;

    /** 下限が無いことを表す番兵。DB では {@code NULL} に写す。 */
    public static final LocalDate UNBOUNDED_START = LocalDate.MIN;

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
        return new DateRange(from, UNBOUNDED_END);
    }

    /**
     * 最終日を含む形（閉区間）から作る。
     *
     * <p>退職日・廃止日のように「その日まで有効」という値は必ずここを通す。
     */
    public static DateRange closed(LocalDate from, LocalDate lastDay) {
        if (lastDay == null) {
            throw new IllegalArgumentException("最終日に null は許されません");
        }
        // ★ 「最終日が番兵」ではなく「翌日が番兵になる」で判定する。
        //   前者だと lastDay = MAX.minusDays(1) が素通りし、黙って無期限の期間になる
        if (!lastDay.isBefore(UNBOUNDED_END.minusDays(1))) {
            throw new IllegalArgumentException(
                    "最終日に番兵を渡さないでください。無期限は startingAt を使います: " + lastDay);
        }
        return new DateRange(from, lastDay.plusDays(1));
    }

    /** 最終日を含む形（閉区間）から作る。{@code lastDay} が空なら無期限。 */
    public static DateRange closed(LocalDate from, Optional<LocalDate> lastDay) {
        return lastDay.map(day -> closed(from, day)).orElseGet(() -> startingAt(from));
    }

    public boolean contains(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("判定する日付に null は許されません");
        }
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

    /**
     * 暦日数。
     *
     * @throws IllegalStateException 端が番兵のとき。無期限の期間に日数は無い
     */
    public long days() {
        if (isUnbounded() || isUnboundedStart()) {
            throw new IllegalStateException("端の無い期間の日数は求められません: " + this);
        }
        return ChronoUnit.DAYS.between(from, toExclusive);
    }

    /** 上限が無いか。 */
    public boolean isUnbounded() {
        return UNBOUNDED_END.equals(toExclusive);
    }

    /** 下限が無いか。 */
    public boolean isUnboundedStart() {
        return UNBOUNDED_START.equals(from);
    }
}
