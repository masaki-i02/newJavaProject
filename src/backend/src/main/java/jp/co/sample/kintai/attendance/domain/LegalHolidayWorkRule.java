package jp.co.sample.kintai.attendance.domain;

import java.util.List;

import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.DayType;

/**
 * <strong>その区間が属する暦日</strong>が法定休日なら
 * {@link PremiumType#LEGAL_HOLIDAY} を付与する（BR-07）。
 *
 * <p>勤務日の区分ではなく暦日で判断する。
 * 休日労働の割増は暦日単位で判断するため（昭 63.1.1 基発 1 号）。
 *
 * <p>{@link CalendarDayBoundaryRule} の後に適用する前提。
 * 区間が暦日をまたいでいると、どちらの暦日で判断すべきか決まらない。
 */
public record LegalHolidayWorkRule(CompanyCalendar calendar) implements AttendanceRule {

    public LegalHolidayWorkRule {
        if (calendar == null) {
            throw new IllegalArgumentException("会社カレンダーに null は許されません");
        }
    }

    @Override
    public List<WorkSlice> apply(List<WorkSlice> slices) {
        return List.copyOf(slices.stream()
                .map(slice -> calendar.dayTypeOf(slice.calendarDate()) == DayType.LEGAL_HOLIDAY
                        ? slice.with(PremiumType.LEGAL_HOLIDAY)
                        : slice)
                .toList());
    }
}
