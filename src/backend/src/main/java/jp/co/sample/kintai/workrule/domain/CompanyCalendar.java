package jp.co.sample.kintai.workrule.domain;

import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DateRange;

/**
 * 会社カレンダー。
 *
 * <p><strong>未登録の日は所定労働日として扱う。</strong>
 * 登録漏れの日を休日と判定すると、通常勤務に休日割増が付いて過払いになる。
 * 逆向きの誤り（休日なのに所定労働日と判定）は、勤怠の確認時に気づける。
 */
public interface CompanyCalendar {

    /** 暦日の区分。未登録の日は {@link DayType#WORKDAY}。 */
    DayType dayTypeOf(LocalDate date);

    /**
     * 指定期間の所定労働日数。
     *
     * <p>期間は半開区間で受ける。月中入社の初月は清算期間が
     * 「入社日から翌月 1 日まで」になり、暦月に固定できないため。
     *
     * <p><strong>既定実装をここに置く。</strong> 数え方は
     * {@link #dayTypeOf(LocalDate)} から一意に決まるので、実装ごとに書き直す理由が無い。
     * 実装側に置くと、半開区間の数え方（末日を含むか）を実装の数だけ間違えられる。
     * テストの代役に置いた場合は、そもそも本番のコードを 1 行も検査していないことになる。
     */
    default int workdayCountIn(DateRange period) {
        if (period == null) {
            throw new IllegalArgumentException("期間に null は許されません");
        }
        int count = 0;
        for (LocalDate date = period.from(); date.isBefore(period.toExclusive());
                date = date.plusDays(1)) {
            if (dayTypeOf(date) == DayType.WORKDAY) {
                count++;
            }
        }
        return count;
    }
}
