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
     */
    int workdayCountIn(DateRange period);
}
