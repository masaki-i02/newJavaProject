package jp.co.sample.kintai.attendance.domain;

import static jp.co.sample.kintai.support.WorkRules.fixedRule;
import static jp.co.sample.kintai.support.WorkRules.flexRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Punches;
import jp.co.sample.kintai.support.TestCalendar;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.WorkRule;

/** 日次集計の単体テスト（UT-ATT-01〜26）。 */
@DisplayName("日次集計")
class DailyAttendanceCalculatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 4, 6);
    private static final LocalDate TUE = LocalDate.of(2026, 4, 7);
    private static final LocalDate SAT = LocalDate.of(2026, 4, 4);
    private static final LocalDate SUN = LocalDate.of(2026, 4, 5);

    private DailyAttendance calculate(LocalDate workDate, Punches punches,
                                      WorkRule rule, TestCalendar calendar) {
        return new DailyAttendanceCalculator(calendar)
                .calculate(workDate, punches.build(), rule);
    }

    private DailyAttendance calculate(LocalDate workDate, Punches punches, WorkRule rule) {
        return calculate(workDate, punches, rule, TestCalendar.allWorkdays());
    }

    /**
     * <strong>この不変条件がすべてのケースで成立する。</strong>
     * 深夜を二重計上する誤りは、この破れとして現れる。
     */
    private void assertBreakdownSumsToWorkingTime(DailyAttendance attendance) {
        assertThat(attendance.baseTime()
                .plus(attendance.overtimeWithinStatutoryTime())
                .plus(attendance.overtimeBeyondStatutoryTime())
                .plus(attendance.legalHolidayTime()))
                .as("UT-ATT-09 内訳の合計 = 実労働時間")
                .isEqualTo(attendance.workingTime());
    }

    @Nested
    @DisplayName("固定時間制の基本")
    class Fixed {

        @Test
        @DisplayName("UT-ATT-01 定時どおりの勤務は全時間が基本時間になる")
        void exactlyScheduled() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("18:00"), fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.breakTime()).isEqualTo(Duration.ofHours(1));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("UT-ATT-02 所定 = 法定 8 時間を超えた分はすべて法定外残業になる")
        void overtimeBeyondStatutory() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("20:00"), fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(10));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeWithinStatutoryTime()).isZero();
            assertThat(result.overtimeBeyondStatutoryTime()).isEqualTo(Duration.ofHours(2));
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("UT-ATT-26 累積が 480 分ちょうどなら法定外残業は 0")
        void exactlyEightHours() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").out("17:00"), fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("UT-ATT-10 打刻が無い日は欠勤として空の結果になる（例外にしない）")
        void noPunches() {
            var result = calculate(MON, Punches.on("2026-04-06"), fixedRule());

            assertThat(result.workingTime()).isZero();
            assertThat(result.slices()).isEmpty();
            assertBreakdownSumsToWorkingTime(result);
        }
    }

    @Nested
    @DisplayName("休日労働（BR-07）")
    class Holidays {

        @Test
        @DisplayName("UT-ATT-03 所定休日に 9 時間労働 → 法定内残業 8 時間 + 法定外残業 1 時間")
        void nonLegalHoliday() {
            var calendar = TestCalendar.allWorkdays().nonLegalHoliday(SAT);
            var result = calculate(SAT, Punches.on("2026-04-04")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("19:00"),
                    fixedRule(), calendar);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(9));
            assertThat(result.baseTime()).as("所定は 0 として扱う").isZero();
            assertThat(result.overtimeWithinStatutoryTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeBeyondStatutoryTime()).isEqualTo(Duration.ofHours(1));
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("UT-ATT-07 法定休日の労働は全時間が法定休日労働。残業に分解されない")
        void legalHoliday() {
            var calendar = TestCalendar.allWorkdays().legalHoliday(SUN);
            var result = calculate(SUN, Punches.on("2026-04-05")
                    .in("09:00").out("19:00"), fixedRule(), calendar);

            assertThat(result.legalHolidayTime()).isEqualTo(Duration.ofHours(10));
            assertThat(result.overtimeWithinStatutoryTime()).isZero();
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
            assertThat(result.baseTime()).isZero();
            assertBreakdownSumsToWorkingTime(result);
        }

        /**
         * <strong>レビューで見つかった欠陥。</strong>
         * 勤務日は土曜だが、日曜 0:00–6:00 は<strong>暦日で判断して</strong>法定休日労働になる。
         * 勤務日の区分を全区間に適用すると、6 時間ぶんの 35% が付かない。
         */
        @Test
        @DisplayName("UT-ATT-17 土曜 22:00 → 日曜（法定休日）06:00 は、日曜分だけが法定休日労働")
        void crossingIntoLegalHoliday() {
            var calendar = TestCalendar.allWorkdays().nonLegalHoliday(SAT).legalHoliday(SUN);
            var result = calculate(SAT, Punches.on("2026-04-04")
                    .in("22:00").out("2026-04-05T06:00"), fixedRule(), calendar);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.legalHolidayTime())
                    .as("日曜 0:00–6:00 の 6 時間だけ")
                    .isEqualTo(Duration.ofHours(6));
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("UT-ATT-18 日曜（法定休日）22:00 → 月曜 06:00 は、日曜分だけが法定休日労働")
        void crossingOutOfLegalHoliday() {
            var calendar = TestCalendar.allWorkdays().legalHoliday(SUN);
            var result = calculate(SUN, Punches.on("2026-04-05")
                    .in("22:00").out("2026-04-06T06:00"), fixedRule(), calendar);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.legalHolidayTime())
                    .as("日曜 22:00–24:00 の 2 時間だけ")
                    .isEqualTo(Duration.ofHours(2));
            assertBreakdownSumsToWorkingTime(result);
        }

        /** 法定休日労働は時間外労働に算入しない（労基法 36 条）。 */
        @Test
        @DisplayName("UT-ATT-19 法定休日をまたぐ勤務で、法定休日部分を残業の累積に数えない")
        void legalHolidayIsNotAccumulated() {
            var calendar = TestCalendar.allWorkdays().legalHoliday(SUN);
            var result = calculate(SUN, Punches.on("2026-04-05")
                    .in("22:00").out("2026-04-06T06:00"), fixedRule(), calendar);

            // 日曜 2 時間は法定休日。月曜 0:00–6:00 の 6 時間は累積 0 から始まる。
            // 勤務日が法定休日なので所定 0 → 6 時間すべてが法定内残業
            assertThat(result.overtimeWithinStatutoryTime()).isEqualTo(Duration.ofHours(6));
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
            assertBreakdownSumsToWorkingTime(result);
        }
    }

    @Nested
    @DisplayName("深夜（BR-06）")
    class Night {

        @Test
        @DisplayName("UT-ATT-04 20:00 – 翌 02:00 → 深夜 4 時間。基本時間 6 時間（残業なし）")
        void nightWithoutOvertime() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("20:00").out("2026-04-07T02:00"), fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(6));
            assertThat(result.nightTime()).isEqualTo(Duration.ofHours(4));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(6));
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
            assertBreakdownSumsToWorkingTime(result);
        }

        /** 深夜は他の区分に重ねて付く。合計には数えない。 */
        @Test
        @DisplayName("UT-ATT-05 日をまたぐ長時間勤務で、深夜と法定外残業が重なって計上される")
        void nightOverlapsOvertime() {
            var result = calculate(TUE, Punches.on("2026-04-07")
                    .in("13:00").breakFrom("18:00").breakTo("19:00").out("2026-04-08T03:00"),
                    fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(13));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeBeyondStatutoryTime()).isEqualTo(Duration.ofHours(5));
            assertThat(result.nightTime())
                    .as("22:00–翌 03:00。法定外残業と完全に重なる")
                    .isEqualTo(Duration.ofHours(5));
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("UT-ATT-25 深夜帯の端点（22:00 は含み、05:00 は含まない）")
        void nightBoundaries() {
            var justBefore = calculate(MON, Punches.on("2026-04-06")
                    .in("21:00").out("22:00"), fixedRule());
            assertThat(justBefore.nightTime()).as("22:00 まではまだ深夜でない").isZero();

            var atFive = calculate(MON, Punches.on("2026-04-06")
                    .in("2026-04-07T04:00").out("2026-04-07T06:00"), fixedRule());
            assertThat(atFive.nightTime()).as("04:00–05:00 の 1 時間だけ")
                    .isEqualTo(Duration.ofHours(1));
        }
    }

    @Nested
    @DisplayName("フレックスタイム制（BR-05）")
    class Flex {

        /**
         * <strong>レビューで見つかった欠陥。</strong>
         * 第 1 版は暦日区分だけで分岐しており、フレックスにも日次の残業を付けていた。
         */
        @Test
        @DisplayName("UT-ATT-20 フレックスで 1 日 10 時間働いても残業は 0。全時間が基本時間")
        void noDailyOvertimeForFlex() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("20:00"), flexRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(10));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(10));
            assertThat(result.overtimeWithinStatutoryTime()).isZero();
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("UT-ATT-21 フレックスでも法定休日労働は制度によらず計上される")
        void legalHolidayAppliesToFlex() {
            var calendar = TestCalendar.allWorkdays().legalHoliday(SUN);
            var result = calculate(SUN, Punches.on("2026-04-05")
                    .in("09:00").out("17:00"), flexRule(), calendar);

            assertThat(result.legalHolidayTime()).isEqualTo(Duration.ofHours(8));
            assertBreakdownSumsToWorkingTime(result);
        }

        @Test
        @DisplayName("フレックスでも深夜は日単位で発生する")
        void nightAppliesToFlex() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("20:00").out("2026-04-07T02:00"), flexRule());

            assertThat(result.nightTime()).isEqualTo(Duration.ofHours(4));
            assertBreakdownSumsToWorkingTime(result);
        }
    }

    @Nested
    @DisplayName("秒の扱い（BR-01）")
    class Seconds {

        /** 開始は切り捨て、終了は切り上げ。<strong>常に労働時間が長くなる側</strong>。 */
        @Test
        @DisplayName("UT-ATT-22 9:00:30 出勤・18:00:30 退勤 → 9:00–18:01 の 541 分")
        void roundsInFavourOfTheEmployee() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("2026-04-06T09:00:30").out("2026-04-06T18:00:30"), fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofMinutes(541));
            assertBreakdownSumsToWorkingTime(result);
        }

        /** 区間ごとに丸めると合計がずれる。丸めは区間へ変換するときの 1 回だけ。 */
        @Test
        @DisplayName("UT-ATT-23 秒を含む勤務を細切れにしても、分割後の合計が分割前と一致する")
        void splittingPreservesTheTotal() {
            var result = calculate(TUE, Punches.on("2026-04-07")
                    .in("2026-04-07T13:00:30").breakFrom("2026-04-07T18:00:20")
                    .breakTo("2026-04-07T19:00:40").out("2026-04-08T03:00:10"), fixedRule());

            Duration sliced = result.slices().stream()
                    .map(WorkSlice::duration).reduce(Duration.ZERO, Duration::plus);
            assertThat(sliced).isEqualTo(result.workingTime());
            assertBreakdownSumsToWorkingTime(result);
        }
    }

    @Nested
    @DisplayName("休憩の検証（BR-08）")
    class Breaks {

        @Test
        @DisplayName("UT-ATT-14 6 時間超で休憩 45 分未満なら警告が立つ。計算値は変えない")
        void breakShortageIsOnlyAWarning() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("12:30").out("18:00"), fixedRule());

            assertThat(result.workingTime())
                    .as("勝手に休憩を差し引かない")
                    .isEqualTo(Duration.ofHours(8).plusMinutes(30));
            assertThat(result.breakRequirementSatisfied()).isFalse();
        }

        @Test
        @DisplayName("6 時間以下なら休憩が無くても満たしている")
        void noBreakNeededUnderSixHours() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").out("15:00"), fixedRule());

            assertThat(result.breakRequirementSatisfied()).isTrue();
        }
    }

    @Nested
    @DisplayName("状態機械（BR-02）")
    class StateMachine {

        @Test
        @DisplayName("UT-ATT-11 退勤打刻が無いと例外")
        void withoutClockOut() {
            assertThatThrownBy(() -> calculate(MON, Punches.on("2026-04-06").in("09:00"),
                    fixedRule()))
                    .isInstanceOf(InvalidTimeClockSequenceException.class)
                    .hasMessageContaining("退勤打刻がないため");
        }

        @Test
        @DisplayName("UT-ATT-12 休憩中に退勤すると例外")
        void clockOutWhileOnBreak() {
            assertThatThrownBy(() -> calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").out("18:00"), fixedRule()))
                    .isInstanceOf(InvalidTimeClockSequenceException.class)
                    .hasMessageContaining("休憩中 の状態では行えません");
        }

        @Test
        @DisplayName("UT-ATT-13 二重の出勤打刻は例外")
        void doubleClockIn() {
            assertThatThrownBy(() -> calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").in("10:00").out("18:00"), fixedRule()))
                    .isInstanceOf(InvalidTimeClockSequenceException.class)
                    .hasMessageContaining("勤務中 の状態では行えません");
        }

        @Test
        @DisplayName("退勤前でも遷移の検査だけなら通る")
        void validateAllowsUnfinished() {
            Punches.on("2026-04-06").in("09:00").build().validateTransitions();
        }

        @Test
        @DisplayName("長さ 0 の区間は記録しない")
        void zeroLengthRangeIsDropped() {
            var result = calculate(MON, Punches.on("2026-04-06")
                    .in("09:00").breakFrom("09:00").breakTo("10:00").out("18:00"), fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.slices()).allSatisfy(
                    slice -> assertThat(slice.duration()).isPositive());
        }
    }

    @Nested
    @DisplayName("暦日境界での分割")
    class CalendarBoundary {

        @Test
        @DisplayName("区間は暦日をまたがない")
        void slicesDoNotCrossMidnight() {
            var result = calculate(TUE, Punches.on("2026-04-07")
                    .in("22:00").out("2026-04-08T03:00"), fixedRule());

            assertThat(result.slices()).allSatisfy(slice ->
                    assertThat(slice.range().start().toLocalDate())
                            .isEqualTo(slice.range().end().minusNanos(1).toLocalDate()));
        }

        @Test
        @DisplayName("暦日区分が同じなら、分割しても結果は変わらない")
        void samedayTypeGivesSameResult() {
            var result = calculate(TUE, Punches.on("2026-04-07")
                    .in("22:00").out("2026-04-08T03:00"), fixedRule());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(5));
            assertThat(result.nightTime()).isEqualTo(Duration.ofHours(5));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(5));
            assertBreakdownSumsToWorkingTime(result);
        }
    }

    @Nested
    @DisplayName("割増の倍率と週次判定の材料")
    class Downstream {

        @Test
        @DisplayName("UT-ATT-06 深夜かつ法定外残業の区間は倍率 1.50 になる")
        void multiplierOfNightOvertime() {
            var result = calculate(TUE, Punches.on("2026-04-07")
                    .in("13:00").breakFrom("18:00").breakTo("19:00").out("2026-04-08T03:00"),
                    fixedRule());
            var rates = jp.co.sample.kintai.workrule.domain.PremiumRates.STATUTORY;

            var nightOvertime = result.slices().stream()
                    .filter(s -> s.has(jp.co.sample.kintai.shared.domain.PremiumType.NIGHT))
                    .findFirst().orElseThrow();

            assertThat(rates.multiplierFor(nightOvertime.premiums()))
                    .isEqualByComparingTo("1.50");
        }

        @Test
        @DisplayName("UT-ATT-08 所定休日は 8 時間まで法定内残業、超過分が法定外残業")
        void nonLegalHolidaySplitsAtEightHours() {
            var calendar = TestCalendar.allWorkdays().nonLegalHoliday(SAT);
            var result = calculate(SAT, Punches.on("2026-04-04")
                    .in("09:00").out("18:00"), fixedRule(), calendar);

            assertThat(result.overtimeWithinStatutoryTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeBeyondStatutoryTime()).isEqualTo(Duration.ofHours(1));
            assertBreakdownSumsToWorkingTime(result);
        }

        /**
         * 日次が保証するのは<strong>週次判定の材料になる法定内労働時間</strong>まで。
         * 週 40 時間超（BR-04）は日次では閉じないので、月次が判定する。
         */
        @Test
        @DisplayName("法定内労働時間 = 実労働 − 法定外残業 − 法定休日労働")
        void statutoryInsideTimeIsDerivable() {
            var calendar = TestCalendar.allWorkdays().legalHoliday(SUN);
            var result = calculate(SUN, Punches.on("2026-04-05")
                    .in("22:00").out("2026-04-06T06:00"), fixedRule(), calendar);

            Duration statutoryInside = result.workingTime()
                    .minus(result.overtimeBeyondStatutoryTime())
                    .minus(result.legalHolidayTime());

            assertThat(statutoryInside).isEqualTo(Duration.ofHours(6));
        }
    }

    /**
     * この不変条件は、上のすべてのケースで
     * {@code assertBreakdownSumsToWorkingTime} として実施している。
     * 集計ロジックの誤り（特に深夜の二重計上）は、この破れとして現れる。
     */
    @Test
    @DisplayName("UT-ATT-09 内訳の合計が実労働時間に一致する（全ケースで実施）")
    void breakdownAlwaysSumsToWorkingTime() {
        var calendar = TestCalendar.allWorkdays().legalHoliday(SUN).nonLegalHoliday(SAT);
        var cases = java.util.List.of(
                calculate(MON, Punches.on("2026-04-06").in("09:00").out("18:00"), fixedRule()),
                calculate(MON, Punches.on("2026-04-06").in("20:00").out("2026-04-07T02:00"),
                        fixedRule()),
                calculate(SAT, Punches.on("2026-04-04").in("22:00").out("2026-04-05T06:00"),
                        fixedRule(), calendar),
                calculate(SUN, Punches.on("2026-04-05").in("22:00").out("2026-04-06T06:00"),
                        fixedRule(), calendar),
                calculate(MON, Punches.on("2026-04-06").in("09:00").out("20:00"), flexRule()),
                calculate(MON, Punches.on("2026-04-06"), fixedRule()));

        assertThat(cases).allSatisfy(this::assertBreakdownSumsToWorkingTime);
    }

    @Test
    @DisplayName("UT-ATT-24 集計値と内訳が食い違う値では生成できない")
    void inconsistentTotalsAreRejected() {
        var slices = calculate(MON, Punches.on("2026-04-06")
                .in("09:00").out("17:00"), fixedRule()).slices();

        assertThatThrownBy(() -> new DailyAttendance(MON,
                jp.co.sample.kintai.workrule.domain.DayType.WORKDAY,
                jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType.FIXED, slices,
                Duration.ofHours(8), Duration.ZERO,
                Duration.ofHours(7), Duration.ofHours(1), Duration.ZERO,
                Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("法定内残業の集計値が内訳と一致しません");
    }

    @Test
    @DisplayName("フレックスに日次の残業を持たせた値では生成できない")
    void flexWithDailyOvertimeIsRejected() {
        var attendance = calculate(MON, Punches.on("2026-04-06")
                .in("09:00").out("19:00"), WorkRules.fixedRule());

        assertThatThrownBy(() -> new DailyAttendance(MON, attendance.dayType(),
                jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType.FLEX,
                attendance.slices(), attendance.workingTime(), attendance.breakTime(),
                attendance.baseTime(), attendance.overtimeWithinStatutoryTime(),
                attendance.overtimeBeyondStatutoryTime(), attendance.nightTime(),
                attendance.legalHolidayTime()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("フレックスに日次の残業を計上しています");
    }
}
