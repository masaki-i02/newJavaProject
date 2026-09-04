package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 法定休日から翌暦日へ持ち越された労働の通算結果（BR-07）。
 *
 * <p>法定休日の労働が翌日 0 時以降に及んだ部分は休日労働ではないので、
 * <strong>その暦日の労働として</strong>時間外労働を判断する
 * （昭 63.3.14 基発 150 号）。同じ暦日に通常のシフトがあれば通算する。
 *
 * @param calendarDate        通算する暦日。<strong>勤務日ではない</strong>
 * @param carriedTime         法定休日の勤務日から持ち越された労働時間
 * @param calendarDayTime     その暦日の労働時間の合計（法定休日労働を除く。持ち越し分を含む）
 * @param alreadyBeyondTime   その暦日で既に法定外残業として計上済みの時間
 * @param additionalOvertime  通算によって新たに法定外残業になった時間
 */
public record HolidayCarryOver(
        LocalDate calendarDate,
        Duration carriedTime,
        Duration calendarDayTime,
        Duration alreadyBeyondTime,
        Duration additionalOvertime) {

    public HolidayCarryOver {
        if (calendarDate == null || carriedTime == null || calendarDayTime == null
                || alreadyBeyondTime == null || additionalOvertime == null) {
            throw new IllegalArgumentException("通算の項目に null は許されません");
        }
        for (Duration value : java.util.List.of(carriedTime, calendarDayTime,
                alreadyBeyondTime, additionalOvertime)) {
            if (value.isNegative()) {
                throw new IllegalArgumentException("労働時間を負にはできません: " + value);
            }
        }
        // ★ 持ち越し分はその暦日の労働の一部である
        if (carriedTime.compareTo(calendarDayTime) > 0) {
            throw new IllegalArgumentException(
                    "持ち越しがその暦日の労働時間を超えています: 持ち越し %s / 暦日 %s"
                            .formatted(carriedTime, calendarDayTime));
        }
        // ★ 通算で足す時間と計上済みの時間の合計が、その暦日の労働を超えることはありえない。
        //   超えるなら、同じ時間を 2 か所で法定外残業に数えている
        if (additionalOvertime.plus(alreadyBeyondTime).compareTo(calendarDayTime) > 0) {
            throw new IllegalArgumentException(
                    "法定外残業が暦日の労働時間を超えています: 追加 %s + 計上済み %s > 暦日 %s"
                            .formatted(additionalOvertime, alreadyBeyondTime, calendarDayTime));
        }
    }

    /** 通算の結果、追加の法定外残業が生じたか。 */
    public boolean hasAdditionalOvertime() {
        return additionalOvertime.isPositive();
    }
}
