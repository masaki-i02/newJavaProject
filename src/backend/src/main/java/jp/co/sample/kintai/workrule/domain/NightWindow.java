package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import jp.co.sample.kintai.shared.domain.TimeOfDayRange;
import jp.co.sample.kintai.shared.domain.TimeRange;

/**
 * 深夜帯（BR-06）。原則 22:00–05:00。
 *
 * <p><strong>値を自由に決められないようにする。</strong>
 * 深夜帯を狭められると、深夜割増を実質的に無効化できてしまう。
 * 割増率の下限だけ守っても、割増の対象になる時間そのものが消えては意味がない
 * （CLAUDE.md 落とし穴 15）。
 *
 * <p>法が認める値は 2 つしかないので {@code enum} で表す。
 * {@code record} に「許可した値だけを通す」検証を書くと、
 * 型の上では任意の時刻を渡せるのに実行時にしか弾けない。
 * 取りうる値が固定なら列挙にして、<strong>不正な値を書けなくする</strong>
 * （CLAUDE.md 4.3）。
 *
 * <p>日付をまたぐ時間帯の展開ロジックもこの型に閉じ込める。
 */
public enum NightWindow {

    /** 労基法 37 条 4 項の原則。 */
    STANDARD(LocalTime.of(22, 0), LocalTime.of(5, 0)),

    /** 厚生労働大臣が定める地域の例外。 */
    DESIGNATED_AREA(LocalTime.of(23, 0), LocalTime.of(6, 0));

    private final LocalTime start;
    private final LocalTime end;

    NightWindow(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public LocalTime start() {
        return start;
    }

    public LocalTime end() {
        return end;
    }

    public boolean crossesMidnight() {
        return start.isAfter(end);
    }

    public TimeOfDayRange asTimeOfDayRange() {
        return new TimeOfDayRange(start, end);
    }

    /**
     * 指定した区間と重なる深夜帯をすべて返す。
     *
     * <p>連続勤務では複数返る。区間は時系列順で、互いに重ならない。
     */
    public List<TimeRange> intervalsOverlapping(TimeRange range) {
        TimeOfDayRange window = asTimeOfDayRange();
        List<TimeRange> found = new ArrayList<>();
        // 深夜帯は日をまたぐので、区間の前日から始まるものも重なりうる
        LocalDate from = range.start().toLocalDate().minusDays(1);
        LocalDate to = range.end().toLocalDate();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            window.on(date).intersect(range).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    /**
     * 指定の時刻が深夜帯に入るか。
     *
     * <p>半開区間として判定する。22:00 は入り、05:00 は入らない。
     */
    public boolean contains(LocalTime time) {
        return crossesMidnight()
                ? !time.isBefore(start) || time.isBefore(end)
                : !time.isBefore(start) && time.isBefore(end);
    }
}
