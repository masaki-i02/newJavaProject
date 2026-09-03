package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;

/**
 * {@link CompanyCalendarRepository} の実装。
 *
 * <p><strong>未登録の日は所定労働日として扱う</strong>（既定値）。
 * 登録漏れの日を休日と判定すると、通常勤務に休日割増が付いて過払いになる。
 * 逆向きの誤りは勤怠の確認時に気づける。
 */
@Repository
class CompanyCalendarRepositoryAdapter implements CompanyCalendarRepository {

    private final CompanyCalendarJpaRepository calendars;

    CompanyCalendarRepositoryAdapter(CompanyCalendarJpaRepository calendars) {
        this.calendars = calendars;
    }

    @Override
    public DayType dayTypeOf(LocalDate date) {
        return calendars.findById(date)
                .map(entity -> DayType.valueOf(entity.getDayType()))
                .orElse(DayType.WORKDAY);
    }

    @Override
    public Map<LocalDate, DayType> findByPeriod(DateRange period) {
        Map<LocalDate, DayType> registered = new LinkedHashMap<>();
        calendars.findByCalendarDateGreaterThanEqualAndCalendarDateLessThan(
                        period.from(), period.toExclusive())
                .forEach(entity -> registered.put(entity.getCalendarDate(),
                        DayType.valueOf(entity.getDayType())));
        return Map.copyOf(registered);
    }

    @Override
    public void save(LocalDate date, DayType dayType, String name) {
        CompanyCalendarEntity entity = calendars.findById(date)
                .orElseGet(() -> new CompanyCalendarEntity(date));
        entity.setDayType(dayType.name());
        entity.setName(name);
        calendars.save(entity);
    }
}
