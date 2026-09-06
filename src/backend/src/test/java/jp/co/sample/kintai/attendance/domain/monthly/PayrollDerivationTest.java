package jp.co.sample.kintai.attendance.domain.monthly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.TestCalendar;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRule;

/**
 * 給与へ渡す 2 つの切り方（UT-PAY-07〜14・17・BR-18）。
 *
 * <p><strong>基礎賃金の要否と、割増の区分は別の切り方である。</strong>
 * 前者は所定との比較だけで決まり、後者は労基法 37 条の区分で決まる。
 * 2 つは入れ子になっていないので、
 * <strong>割増が付いているのに基礎賃金の追加が要らない月</strong>が存在する。
 *
 * <p>値は<strong>本番の計算を通して</strong>作る。手で組み立てた月次清算に当てると、
 * 導出メソッドが実際の計算結果と噛み合うかを 1 行も検査しない（落とし穴 37・55）。
 */
@DisplayName("給与へ渡す基礎賃金と割増（BR-18）")
class PayrollDerivationTest {

    private static final EmployeeId TARO = new EmployeeId(UUID.randomUUID());
    private static final DateRange EMPLOYED = DateRange.startingAt(LocalDate.of(2020, 4, 1));

    private final TestCalendar calendar = TestCalendar.allWorkdays();
    private final DailyAttendances daily = new DailyAttendances(calendar);
    private final MonthlySettlementCalculator calculator =
            new MonthlySettlementCalculator(calendar);

    /** 所定の範囲に収まった月。所定内 = 実労働、所定超 = 0。 */
    @Test
    @DisplayName("UT-PAY-07 所定の範囲に収まる月は所定超が立たない")
    void withinScheduledTime() {
        weekdaysOnly(YearMonth.of(2026, 5));
        // 2026 年 5 月の所定労働日は 21 日（土日以外）。全日を 8 時間ちょうど働く
        var days = workdaysOf(YearMonth.of(2026, 5), Duration.ofHours(8));

        var settlement = calculator.calculate(TARO, period(2026, 5), days,
                WorkRules.fixedRule(), Duration.ZERO, 0);

        assertThat(settlement.scheduledInsideTime())
                .as("所定の範囲で働いた時間").isEqualTo(settlement.workingTime());
        assertThat(settlement.beyondScheduledTime())
                .as("1 分も所定を超えていない").isZero();
        assertThat(settlement.overtimeTime()).isZero();
    }

    /**
     * <strong>所定休日に働いた 8 時間は、割増が付かないのに 1.0 倍の追加支払が要る。</strong>
     * 週 40 時間以内なので法定外残業にならない（法定内残業）。
     * ここを「割増が付かないから月給に含まれる」と扱うと、賃金の不払いになる。
     */
    @Test
    @DisplayName("UT-PAY-08 所定休日に働くと割増は付かないが所定超が立つ")
    void workOnNonLegalHoliday() {
        weekdaysOnly(YearMonth.of(2026, 5));
        List<DailyAttendance> days = new ArrayList<>(
                workdaysOf(YearMonth.of(2026, 5), Duration.ofHours(8)));
        // 5/9 は土曜（所定休日）。所定が無い日なので、働いた 8 時間は所定の外になる
        days.add(daily.fixedDay(LocalDate.of(2026, 5, 9), Duration.ofHours(8)));

        var settlement = calculator.calculate(TARO, period(2026, 5), days,
                WorkRules.fixedRule(), Duration.ZERO, 0);

        assertThat(settlement.beyondScheduledTime())
                .as("所定休日の 8 時間は所定の外。1.0 倍の追加支払が要る")
                .isEqualTo(Duration.ofHours(8));
        assertThat(settlement.scheduledInsideTime())
                .as("所定労働日 21 日 × 8 時間").isEqualTo(Duration.ofHours(168));
    }

    /**
     * <strong>所定に届いていないのに時間外労働が生じる月。</strong>
     *
     * <p>所定総が法定総枠を上回る月（BR-05 が警告つきで認めている）に、
     * 対象労働時間が所定を下回ったとき。
     * <strong>支払うのは割増だけで、基礎賃金 1.0 倍は月給に含まれている。</strong>
     * 第 1 版の設計はここで 115 分を二重に払っていた（落とし穴 119）。
     */
    @Test
    @DisplayName("UT-PAY-09 所定総が総枠を上回る月は、所定超 0 でも時間外が立つ")
    void overtimeWithoutBeyondScheduled() {
        // 2026 年 6 月は 30 日。総枠 = 30 ÷ 7 × 40 時間 = 10,285 分
        YearMonth june = YearMonth.of(2026, 6);
        // 所定労働日を 22 日にすると所定総 = 10,560 分 > 総枠 10,285 分
        weekdaysOnly(june);
        var days = daily.flexDaysTotalling(LocalDate.of(2026, 6, 1),
                Duration.ofMinutes(10_400), Duration.ofMinutes(600));

        var settlement = calculator.calculate(TARO, period(2026, 6), days,
                WorkRules.flexRule(), Duration.ZERO, 0);

        assertThat(settlement.scheduledTotalTime())
                .as("所定総が総枠を上回る月であること")
                .isGreaterThan(settlement.statutoryTotalLimit());
        assertThat(settlement.overtimeTime())
                .as("総枠を超えた分").isEqualTo(Duration.ofMinutes(115));
        assertThat(settlement.shortageTime())
                .as("所定には届いていない").isEqualTo(Duration.ofMinutes(160));
        assertThat(settlement.beyondScheduledTime())
                .as("所定を 1 分も超えていないので、基礎賃金の追加は要らない").isZero();
        assertThat(settlement.scheduledInsideTime())
                .isEqualTo(Duration.ofMinutes(10_400));
    }

    /**
     * <strong>1 週の所定が 40 時間を超える週。</strong>
     *
     * <p>土曜も所定労働日にすると、週の所定が 48 時間になる。
     * 週 40 時間超（BR-04）が 8 時間立つが、<strong>所定は 1 分も超えていない。</strong>
     * 支払うのは 0.25 の割増だけで、基礎賃金 1.0 倍は月給に含まれている。
     * 「割増が付く時間 = 基礎賃金の追加が要る時間」と考えると、この月で二重に払う。
     */
    @Test
    @DisplayName("UT-PAY-10 週 40 時間超が立っても所定超は 0 になりうる")
    void weeklyOvertimeWithoutBeyondScheduled() {
        // 日曜だけを法定休日にする。土曜は所定労働日
        YearMonth may = YearMonth.of(2026, 5);
        for (LocalDate date = may.atDay(1); date.isBefore(may.plusMonths(1).atDay(1));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                calendar.legalHoliday(date);
            }
        }
        // 5/4(月) 〜 5/9(土) の 6 日を 8 時間ずつ。週の法定内は 48 時間
        var days = daily.week(LocalDate.of(2026, 5, 4), 6, Duration.ofHours(8));

        var settlement = calculator.calculate(TARO, period(2026, 5), days,
                WorkRules.fixedRule(), Duration.ZERO, 0);

        assertThat(settlement.weeklyOvertimeTime())
                .as("週 40 時間を超えた 8 時間").isEqualTo(Duration.ofHours(8));
        assertThat(settlement.beyondScheduledTime())
                .as("働いた 48 時間はすべて所定の内側。基礎賃金の追加は要らない").isZero();
        assertThat(settlement.scheduledInsideTime()).isEqualTo(Duration.ofHours(48));
    }

    /**
     * <strong>法定休日には所定が無いので、法定休日労働は必ず所定超に入る。</strong>
     * 1.0 + 0.35 が支払われる。
     */
    @Test
    @DisplayName("UT-PAY-11 法定休日労働は所定超と法定休日の両方に入る")
    void legalHolidayWork() {
        weekdaysOnly(YearMonth.of(2026, 5));
        List<DailyAttendance> days = new ArrayList<>(
                workdaysOf(YearMonth.of(2026, 5), Duration.ofHours(8)));
        // 5/10 は日曜（法定休日）
        days.add(daily.legalHolidayDay(LocalDate.of(2026, 5, 10), Duration.ofHours(8)));

        var settlement = calculator.calculate(TARO, period(2026, 5), days,
                WorkRules.fixedRule(), Duration.ZERO, 0);

        assertThat(settlement.legalHolidayTime()).isEqualTo(Duration.ofHours(8));
        assertThat(settlement.beyondScheduledTime())
                .as("法定休日の所定は 0 なので、働いた 8 時間はすべて所定の外")
                .isEqualTo(Duration.ofHours(8));
    }

    /** 60 時間ちょうどでは 50% の対象にならない（労基法 37 条 1 項但書）。 */
    @Test
    @DisplayName("UT-PAY-12 時間外が 60 時間ちょうどなら 50% の対象は 0")
    void exactlySixtyHours() {
        weekdaysOnly(YearMonth.of(2026, 6));
        var days = daily.flexDaysTotalling(LocalDate.of(2026, 6, 1),
                Duration.ofMinutes(10_285 + 3_600), Duration.ofMinutes(700));

        var settlement = calculator.calculate(TARO, period(2026, 6), days,
                WorkRules.flexRule(), Duration.ZERO, 0);

        assertThat(settlement.overtimeTime()).isEqualTo(Duration.ofHours(60));
        assertThat(settlement.overtimeOver60Time())
                .as("超えた分だけが 50%。ちょうどは含まない").isZero();
    }

    @Test
    @DisplayName("UT-PAY-13 時間外が 60 時間を 1 分超えると 50% の対象が 1 分立つ")
    void oneMinuteOverSixtyHours() {
        weekdaysOnly(YearMonth.of(2026, 6));
        var days = daily.flexDaysTotalling(LocalDate.of(2026, 6, 1),
                Duration.ofMinutes(10_285 + 3_601), Duration.ofMinutes(700));

        var settlement = calculator.calculate(TARO, period(2026, 6), days,
                WorkRules.flexRule(), Duration.ZERO, 0);

        assertThat(settlement.overtimeOver60Time()).isEqualTo(Duration.ofMinutes(1));
    }

    /**
     * <strong>深夜は重複属性なので、所定内・所定超のどちらも増やさない。</strong>
     *
     * <p><strong>「所定内 + 所定超 = 実労働」を期待に書かない。</strong>
     * {@code beyondScheduledTime()} が {@code workingTime − scheduledInsideTime()}
     * として定義されている以上、その等式は<strong>定義を代入しただけ</strong>で、
     * 深夜を足し込む実装でも成り立つ（CLAUDE.md 落とし穴 117）。実数で書く。
     *
     * <p>5 月の平日は 21 日。所定総は 21 × 480 分だが、働いたのは 1 日 8 時間だけである。
     */
    @Test
    @DisplayName("UT-PAY-14 深夜労働があっても所定内は実労働のまま増えない")
    void nightWorkDoesNotChangeTheSplit() {
        weekdaysOnly(YearMonth.of(2026, 5));
        var days = List.of(daily.flexNightDay(LocalDate.of(2026, 5, 7),
                Duration.ofHours(8), Duration.ofHours(2)));

        var settlement = calculator.calculate(TARO, period(2026, 5), days,
                WorkRules.flexRule(), Duration.ZERO, 0);

        assertThat(settlement.nightTime()).isEqualTo(Duration.ofHours(2));
        assertThat(settlement.workingTime())
                .as("働いたのは 1 日 8 時間だけ").isEqualTo(Duration.ofHours(8));
        assertThat(settlement.scheduledInsideTime())
                .as("所定の範囲で働いた 8 時間。深夜の 2 時間を足さない")
                .isEqualTo(Duration.ofHours(8));
        assertThat(settlement.beyondScheduledTime())
                .as("所定に届いていないので所定超は無い").isZero();
    }

    /**
     * <strong>年休を承認した日に出勤した月。</strong>
     *
     * <p>所定総からその日が除かれるのに実労働は乗るので、
     * 所定の範囲で働いた実績が所定総を超える。
     * 所定内は所定総で頭打ちになり、超えた分は所定超に入る。
     * <strong>社員に不利にならない側へ倒れる。</strong>
     */
    @Test
    @DisplayName("UT-PAY-17 年休の日に出勤した月は所定内が所定総で頭打ちになる")
    void workedOnPaidLeaveDay() {
        weekdaysOnly(YearMonth.of(2026, 5));
        var days = workdaysOf(YearMonth.of(2026, 5), Duration.ofHours(8));

        // 21 日すべてに出勤しているのに、1 日は年休として承認されている
        var settlement = calculator.calculate(TARO, period(2026, 5), days,
                WorkRules.fixedRule(), Duration.ZERO, 1);

        assertThat(settlement.scheduledTotalTime())
                .as("所定労働日 21 日から年休の 1 日を除いた 20 日 × 8 時間")
                .isEqualTo(Duration.ofHours(160));
        assertThat(settlement.scheduledInsideTime())
                .as("実績 168 時間は所定総で頭打ちになる").isEqualTo(Duration.ofHours(160));
        assertThat(settlement.beyondScheduledTime())
                .as("超えた 8 時間は 1.0 倍の追加支払の対象になる")
                .isEqualTo(Duration.ofHours(8));
    }

    private List<DailyAttendance> workdaysOf(YearMonth month, Duration worked) {
        List<DailyAttendance> days = new ArrayList<>();
        for (LocalDate date = month.atDay(1); date.isBefore(month.plusMonths(1).atDay(1));
                date = date.plusDays(1)) {
            if (calendar.dayTypeOf(date) == jp.co.sample.kintai.workrule.domain.DayType.WORKDAY) {
                days.add(daily.fixedDay(date, worked));
            }
        }
        return List.copyOf(days);
    }

    private void weekdaysOnly(YearMonth month) {
        for (LocalDate date = month.atDay(1); date.isBefore(month.plusMonths(1).atDay(1));
                date = date.plusDays(1)) {
            switch (date.getDayOfWeek()) {
                case SUNDAY -> calendar.legalHoliday(date);
                case SATURDAY -> calendar.nonLegalHoliday(date);
                default -> { }
            }
        }
    }

    private static SettlementPeriod period(int year, int month) {
        return SettlementPeriod.of(YearMonth.of(year, month), EMPLOYED).orElseThrow();
    }
}
