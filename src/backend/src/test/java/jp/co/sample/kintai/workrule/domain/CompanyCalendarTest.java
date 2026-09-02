package jp.co.sample.kintai.workrule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * 期間は半開区間。{@code [6/1, 7/1)} は 6/30 を含み 7/1 を含まない。
     *
     * <p><strong>両端を休日にしない。</strong>
     * 第 1 版は 6/30 と 7/1 の両方を休日にしていたため、
     * 数える範囲を末日ぶんずらしても結果が 29 のまま動かず、
     * 境界のどちらの変異にも反応しなかった。
     * 末日だけを休日にし、その翌日は所定労働日のままにする。
     */
    @Test
    @DisplayName("UT-CAL-03 期間は半開区間。末日は含み、その翌日は含まない")
    void periodIsHalfOpen() {
        var calendar = TestCalendar.allWorkdays().legalHoliday(LocalDate.of(2026, 6, 30));
        var june = new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        // 6/30 を休日にした分だけ 30 日から減る。7/1 は所定労働日だが数に入らない
        assertThat(calendar.workdayCountIn(june)).isEqualTo(29);

        // 末日を落とすと 6/30 が二重に効いてしまうので、翌日側でも確かめる
        var throughJuly1 = new DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 2));
        assertThat(calendar.workdayCountIn(throughJuly1))
                .as("7/1 は所定労働日なので 1 日ぶん増える")
                .isEqualTo(30);
    }

    /**
     * 端が番兵の期間は数えられない。
     *
     * <p>{@code LocalDate.MAX} まで 1 日ずつ回すと約 21 億回になり、応答が返らない。
     * {@code DateRange.days()} が番兵を弾いているのと同じ理由でここでも弾く
     * （CLAUDE.md 落とし穴 35）。
     */
    @Test
    @DisplayName("UT-CAL-04 無期限の期間では所定労働日数を数えない")
    void unboundedPeriodIsRejected() {
        var calendar = TestCalendar.allWorkdays();

        assertThatThrownBy(() ->
                calendar.workdayCountIn(DateRange.startingAt(LocalDate.of(2026, 6, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("端の無い期間の所定労働日数は数えられません");
    }
}
