package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * 1 週間の週 40 時間超（BR-04）。
 *
 * @param weekStart         週の起算日（日曜）
 * @param weekEndExclusive  週の終了日。<strong>含まない</strong>（次の日曜）
 * @param statutoryInsideTime その週の法定内労働時間の合計
 * @param overtimeTime      40 時間を超えた分。超えていなければ 0
 * @param overtimeByMonth   超過を<strong>発生した暦日の属する月</strong>で分けたもの。
 *                          週が月をまたぐと 2 つの月に分かれる
 */
public record WeeklyOvertime(LocalDate weekStart, LocalDate weekEndExclusive,
                             Duration statutoryInsideTime, Duration overtimeTime,
                             Map<YearMonth, Duration> overtimeByMonth) {

    public WeeklyOvertime {
        if (weekStart == null || weekEndExclusive == null
                || statutoryInsideTime == null || overtimeTime == null
                || overtimeByMonth == null) {
            throw new IllegalArgumentException("週次時間外の項目に null は許されません");
        }
        overtimeByMonth = Map.copyOf(overtimeByMonth);
        if (!weekStart.isBefore(weekEndExclusive)) {
            throw new IllegalArgumentException(
                    "週の開始は終了より前である必要があります: [%s, %s)"
                            .formatted(weekStart, weekEndExclusive));
        }
        if (statutoryInsideTime.isNegative() || overtimeTime.isNegative()) {
            throw new IllegalArgumentException(
                    "労働時間を負にはできません: 法定内 %s / 時間外 %s"
                            .formatted(statutoryInsideTime, overtimeTime));
        }
        // 超過分が法定内労働そのものを上回ることはありえない
        if (overtimeTime.compareTo(statutoryInsideTime) > 0) {
            throw new IllegalArgumentException(
                    "週の時間外が法定内労働を超えています: 時間外 %s / 法定内 %s"
                            .formatted(overtimeTime, statutoryInsideTime));
        }
        // ★ 月ごとの内訳の合計は、週の超過そのものである。
        //   食い違うと、どこかの月で計上漏れか二重計上が起きている
        Duration byMonth = overtimeByMonth.values().stream()
                .reduce(Duration.ZERO, Duration::plus);
        if (!byMonth.equals(overtimeTime)) {
            throw new IllegalArgumentException(
                    "月ごとの内訳の合計が週の時間外と一致しません: 内訳 %s / 週 %s"
                            .formatted(byMonth, overtimeTime));
        }
        for (Map.Entry<YearMonth, Duration> entry : overtimeByMonth.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().isNegative()) {
                throw new IllegalArgumentException(
                        "月ごとの内訳が不正です: " + entry);
            }
        }
    }

    /**
     * その月に計上される超過。
     *
     * <p><strong>超過が発生した暦日の属する月で振り分ける</strong>（設計書 3.2）。
     * 週の合計を末日の月へ寄せると、月末が金曜の月に退職した社員の最終週が
     * <strong>存在しない翌月に割り当てられ、誰にも計上されなくなる。</strong>
     * 在職中でも、7 月に働いた分の割増が 8 月の給与になり毎月払い（労基法 24 条）に反する。
     */
    public Duration overtimeChargedTo(YearMonth month) {
        return overtimeByMonth.getOrDefault(month, Duration.ZERO);
    }

    /** その週が労働時間を持つ月。日付の昇順。 */
    public java.util.SortedSet<YearMonth> chargedMonths() {
        return new java.util.TreeSet<>(overtimeByMonth.keySet());
    }

    public boolean hasOvertime() {
        return overtimeTime.isPositive();
    }
}
