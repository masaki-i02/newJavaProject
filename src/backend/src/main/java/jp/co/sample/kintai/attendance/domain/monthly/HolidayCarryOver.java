package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 法定休日から翌暦日へ持ち越された労働の通算結果（BR-07）。
 *
 * <p>法定休日の労働が翌日 0 時以降に及んだ部分は休日労働ではないので、
 * <strong>その暦日の労働として</strong>時間外労働を判断する
 * （昭 63.3.14 基発 150 号）。同じ暦日に通常のシフトがあれば通算する。
 *
 * <p><strong>追加の法定外残業は勤務日ごとに持つ。</strong>
 * 合計だけにすると、この時間を週 40 時間の判定から引くときに
 * 「どの週から引くか」が決まらない。法定休日を日曜以外にした瞬間、
 * 時間を数えた週と引く週がずれて二重計上が復活する。
 *
 * @param calendarDate         通算する暦日。<strong>勤務日ではない</strong>
 * @param carriedTime          法定休日の勤務日から持ち越された労働時間
 * @param calendarDayTime      その暦日の労働時間の合計（法定休日労働を除く。持ち越し分を含む）
 * @param alreadyBeyondTime    その暦日で既に法定外残業として計上済みの時間
 * @param additionalByWorkDate 通算によって新たに法定外残業になった時間。<strong>勤務日ごと</strong>
 */
public record HolidayCarryOver(
        LocalDate calendarDate,
        Duration carriedTime,
        Duration calendarDayTime,
        Duration alreadyBeyondTime,
        Map<LocalDate, Duration> additionalByWorkDate) {

    public HolidayCarryOver {
        if (calendarDate == null || carriedTime == null || calendarDayTime == null
                || alreadyBeyondTime == null || additionalByWorkDate == null) {
            throw new IllegalArgumentException("通算の項目に null は許されません");
        }
        additionalByWorkDate = Map.copyOf(additionalByWorkDate);
        for (Duration value : List.of(carriedTime, calendarDayTime, alreadyBeyondTime)) {
            if (value.isNegative()) {
                throw new IllegalArgumentException("労働時間を負にはできません: " + value);
            }
        }
        for (Duration value : additionalByWorkDate.values()) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException("追加の法定外残業を負にはできません: " + value);
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
        Duration additional = additionalByWorkDate.values().stream()
                .reduce(Duration.ZERO, Duration::plus);
        if (additional.plus(alreadyBeyondTime).compareTo(calendarDayTime) > 0) {
            throw new IllegalArgumentException(
                    "法定外残業が暦日の労働時間を超えています: 追加 %s + 計上済み %s > 暦日 %s"
                            .formatted(additional, alreadyBeyondTime, calendarDayTime));
        }
    }

    /** 通算によって新たに法定外残業になった時間の合計。 */
    public Duration additionalOvertime() {
        return additionalByWorkDate.values().stream()
                .reduce(Duration.ZERO, Duration::plus);
    }

    /** 通算の結果、追加の法定外残業が生じたか。 */
    public boolean hasAdditionalOvertime() {
        return additionalOvertime().isPositive();
    }

    /** 勤務日ごとの追加分を、日付の昇順で。 */
    public Map<LocalDate, Duration> sortedByWorkDate() {
        return new TreeMap<>(additionalByWorkDate);
    }
}
