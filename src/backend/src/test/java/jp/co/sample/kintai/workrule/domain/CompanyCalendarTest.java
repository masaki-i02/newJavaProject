package jp.co.sample.kintai.workrule.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.support.TestCalendar;

/** 会社カレンダーの単体テスト（UT-CAL-01〜03）。 */
@DisplayName("会社カレンダー")
class CompanyCalendarTest {

    /**
     * 登録漏れの日を休日と判定すると、通常勤務に休日割増が付いて<strong>過払いになる。</strong>
     * 逆向きの誤りは勤怠の確認時に気づける。
     */
    @Test
    @DisplayName("UT-CAL-01 未登録の日は所定労働日として扱う")
    void unregisteredDayIsAWorkday() {
        assertThat(TestCalendar.allWorkdays().dayTypeOf(LocalDate.of(2026, 6, 1)))
                .isEqualTo(DayType.WORKDAY);
    }

    @Test
    @DisplayName("UT-CAL-02 所定労働日数は土日祝を除いた日数になる")
    void workdayCountExcludesHolidays() {
        var calendar = TestCalendar.allWorkdays();
        for (LocalDate d = LocalDate.of(2026, 6, 1); d.isBefore(LocalDate.of(2026, 7, 1));
                d = d.plusDays(1)) {
            switch (d.getDayOfWeek()) {
                case SUNDAY -> calendar.legalHoliday(d);
                case SATURDAY -> calendar.nonLegalHoliday(d);
                default -> { }
            }
        }

        assertThat(calendar.workdayCountIn(
                new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1))))
                .isEqualTo(22);
    }

    @Test
    @DisplayName("UT-CAL-03 期間は半開区間。[6/1, 7/1) は 6/30 を含み 7/1 を含まない")
    void periodIsHalfOpen() {
        var calendar = TestCalendar.allWorkdays()
                .legalHoliday(LocalDate.of(2026, 6, 30))
                .legalHoliday(LocalDate.of(2026, 7, 1));
        var june = new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        // 6/30 を休日にした分だけ減り、7/1 を休日にしたことは影響しない
        assertThat(calendar.workdayCountIn(june)).isEqualTo(29);
    }
}
