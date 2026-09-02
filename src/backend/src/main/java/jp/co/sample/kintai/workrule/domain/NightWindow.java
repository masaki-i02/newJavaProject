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
 * <p>日付をまたぐ時間帯の展開ロジックもこの型に閉じ込める。
 */
public record NightWindow(LocalTime start, LocalTime end) {

    /** 労基法 37 条 4 項の原則。 */
    public static final NightWindow STANDARD =
            new NightWindow(LocalTime.of(22, 0), LocalTime.of(5, 0));

    /** 厚生労働大臣が定める地域の例外。 */
    public static final NightWindow DESIGNATED_AREA =
            new NightWindow(LocalTime.of(23, 0), LocalTime.of(6, 0));

    public NightWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("深夜帯に null は許されません");
        }
        boolean legal = (start.equals(LocalTime.of(22, 0)) && end.equals(LocalTime.of(5, 0)))
                || (start.equals(LocalTime.of(23, 0)) && end.equals(LocalTime.of(6, 0)));
        if (!legal) {
            throw new IllegalArgumentException(
                    "深夜帯は 22:00–05:00 または 23:00–06:00 に限ります: %s–%s"
                            .formatted(start, end));
        }
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
        List<TimeRange> found = new ArrayList<>();
        // 深夜帯は日をまたぐので、区間の前日から始まるものも重なりうる
        LocalDate from = range.start().toLocalDate().minusDays(1);
        LocalDate to = range.end().toLocalDate();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            asTimeOfDayRange().on(date).intersect(range).ifPresent(found::add);
        }
        return List.copyOf(found);
    }
}
