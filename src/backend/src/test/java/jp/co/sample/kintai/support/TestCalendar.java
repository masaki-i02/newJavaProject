package jp.co.sample.kintai.support;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;

/**
 * テスト用の会社カレンダー。
 *
 * <p>既定は所定労働日。<strong>本番と同じ既定にそろえる。</strong>
 * 登録漏れの日を休日と判定すると、通常勤務に休日割増が付いて過払いになる。
 *
 * <p>代役が持つのは「その日が何の日か」だけである。
 * 日数の数え方は {@link CompanyCalendar} の既定実装に任せる。
 * ここで数え直すと、テストが本番のコードを 1 行も検査しないものになる。
 */
public final class TestCalendar implements CompanyCalendar {

    private final Map<LocalDate, DayType> registered = new HashMap<>();

    public static TestCalendar allWorkdays() {
        return new TestCalendar();
    }

    public TestCalendar legalHoliday(LocalDate date) {
        registered.put(date, DayType.LEGAL_HOLIDAY);
        return this;
    }

    public TestCalendar nonLegalHoliday(LocalDate date) {
        registered.put(date, DayType.NON_LEGAL_HOLIDAY);
        return this;
    }

    @Override
    public DayType dayTypeOf(LocalDate date) {
        return registered.getOrDefault(date, DayType.WORKDAY);
    }
}
