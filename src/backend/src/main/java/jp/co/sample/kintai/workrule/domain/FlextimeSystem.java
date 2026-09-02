package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.TimeOfDayRange;

/**
 * フレックスタイム制。日々の所定は無く、清算期間の総労働時間で管理する。
 *
 * <p>朝夕のフレキシブル帯を独立した項目として持たず、外枠とコアタイムから導出する。
 * 3 つを独立に持つと「コアタイムがフレキシブル帯の外にある」という矛盾したデータを作れてしまう。
 *
 * @param flexibleTime             出退勤を自由に決められる範囲（07:00–22:00 など）
 * @param coreTime                 必ず労働する範囲（11:00–15:00 など）
 * @param standardDailyWorkingTime 所定総労働時間の算出に使う 1 日あたりの時間。
 *                                 <strong>日々の残業判定には使わない</strong>
 */
public record FlextimeSystem(TimeOfDayRange flexibleTime, TimeOfDayRange coreTime,
                             Duration standardDailyWorkingTime) implements WorkingTimeSystem {

    public FlextimeSystem {
        if (flexibleTime == null || coreTime == null || standardDailyWorkingTime == null) {
            throw new IllegalArgumentException("フレックスの項目に null は許されません");
        }
        if (!flexibleTime.contains(coreTime)) {
            throw new IllegalArgumentException(
                    "コアタイムはフレキシブルタイムの内側である必要があります: コア %s / フレキシブル %s"
                            .formatted(coreTime, flexibleTime));
        }
        if (!standardDailyWorkingTime.isPositive()) {
            throw new IllegalArgumentException(
                    "1 日あたりの時間は正である必要があります: " + standardDailyWorkingTime);
        }
    }

    /** 清算期間の所定総労働時間。= 所定労働日数 × 1 日あたりの時間。 */
    public Duration scheduledTotalWorkingTime(int workdayCount) {
        if (workdayCount < 0) {
            throw new IllegalArgumentException("所定労働日数を負にはできません: " + workdayCount);
        }
        return standardDailyWorkingTime.multipliedBy(workdayCount);
    }

    /**
     * コアタイム前のフレキシブル帯（07:00–11:00 など）。
     *
     * <p>コアタイムが外枠の先頭から始まる規則では帯が存在しないので空を返す。
     * これは矛盾ではなく<strong>正当な設定</strong>なので、例外にしない。
     */
    public Optional<TimeOfDayRange> flexibleMorning() {
        return flexibleTime.start().equals(coreTime.start())
                ? Optional.empty()
                : Optional.of(new TimeOfDayRange(flexibleTime.start(), coreTime.start()));
    }

    /**
     * コアタイム後のフレキシブル帯（15:00–22:00 など）。
     *
     * <p>コアタイムが外枠の末尾で終わる規則では帯が存在しないので空を返す。
     */
    public Optional<TimeOfDayRange> flexibleEvening() {
        return coreTime.end().equals(flexibleTime.end())
                ? Optional.empty()
                : Optional.of(new TimeOfDayRange(coreTime.end(), flexibleTime.end()));
    }
}
