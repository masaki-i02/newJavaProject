package jp.co.sample.kintai.support;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
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
 *
 * <p><strong>「登録されている」と「所定労働日である」は別の事実である。</strong>
 * {@link #dayTypeOf} はどちらも {@code WORKDAY} を返すので、
 * 未登録の年度を拒否する規則（BR-18）の入力は {@link #findByPeriod} でしか表現できない。
 * そのために {@link CompanyCalendarRepository} まで実装する。
 */
public final class TestCalendar implements CompanyCalendarRepository {

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

    /**
     * <strong>明示的に所定労働日として登録する。</strong>
     *
     * <p>既定と同じ区分だが、「登録されている」という事実が別に要る場面がある。
     */
    public TestCalendar workday(LocalDate date) {
        registered.put(date, DayType.WORKDAY);
        return this;
    }

    /** 期間の全日を、土=所定休日・日=法定休日・他=所定労働日として登録する。 */
    public TestCalendar registerAll(DateRange period) {
        for (LocalDate date = period.from(); date.isBefore(period.toExclusive());
                date = date.plusDays(1)) {
            switch (date.getDayOfWeek()) {
                case SUNDAY -> legalHoliday(date);
                case SATURDAY -> nonLegalHoliday(date);
                default -> workday(date);
            }
        }
        return this;
    }

    @Override
    public DayType dayTypeOf(LocalDate date) {
        return registered.getOrDefault(date, DayType.WORKDAY);
    }

    /** 登録されている暦日区分。<strong>未登録の日はキーごと現れない。</strong> */
    @Override
    public Map<LocalDate, DayType> findByPeriod(DateRange period) {
        Map<LocalDate, DayType> found = new HashMap<>();
        registered.forEach((date, dayType) -> {
            if (period.contains(date)) {
                found.put(date, dayType);
            }
        });
        return Map.copyOf(found);
    }

    @Override
    public void save(LocalDate date, DayType dayType, String name) {
        registered.put(date, dayType);
    }
}
