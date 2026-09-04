package jp.co.sample.kintai.attendance.domain.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.Punches;
import jp.co.sample.kintai.support.TestCalendar;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystem;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 月次清算（UT-BR05-01〜19）。
 *
 * <p>要点は<strong>3 つの時間を区別すること</strong>である。
 * 対象労働時間（実績）・所定総労働時間（契約上の約束）・法定労働時間の総枠（法の上限）は
 * それぞれ別のものさしで、混同すると時間外でない労働に割増を付けるか、
 * 逆に割増すべき時間を見落とす。
 */
@DisplayName("月次清算（BR-05）")
class MonthlySettlementCalculatorTest {

    private static final EmployeeId TARO = new EmployeeId(UUID.randomUUID());
    private static final Duration WEEKLY = Duration.ofHours(40);
    private static final jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId SERIES =
            new jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId(UUID.randomUUID());

    /** 在籍期間に制限が無い社員。 */
    private static final DateRange EMPLOYED = DateRange.startingAt(LocalDate.of(2020, 4, 1));

    private final TestCalendar calendar = TestCalendar.allWorkdays();
    private final MonthlySettlementCalculator calculator =
            new MonthlySettlementCalculator(calendar);

    private static SettlementPeriod period(int year, int month) {
        return SettlementPeriod.of(YearMonth.of(year, month), EMPLOYED).orElseThrow();
    }

    private static WorkRule flexRule() {
        return WorkRules.versionOf(new jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId(
                        UUID.randomUUID()), LocalDate.of(2020, 4, 1),
                WorkRules.flex(), Duration.ofHours(8), NightWindow.STANDARD);
    }

    private static WorkRule fixedRule() {
        return WorkRules.versionOf(new jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId(
                        UUID.randomUUID()), LocalDate.of(2020, 4, 1),
                WorkRules.fixed(), Duration.ofHours(8), NightWindow.STANDARD);
    }

    /** その月の平日に {@code perDay} ずつ働いた日次を作る。 */
    private static List<DailyAttendance> flexDays(YearMonth month, int days,
                                                  Duration perDay) {
        List<DailyAttendance> result = new ArrayList<>();
        LocalDate date = month.atDay(1);
        for (int i = 0; i < days; i++) {
            result.add(DailyAttendances.flexDay(date.plusDays(i), perDay));
        }
        return result;
    }

    @Nested
    @DisplayName("法定労働時間の総枠")
    class StatutoryLimit {

        /** 総枠は {@code SettlementPeriod} が持つ。本コンテキストで計算式を再実装しない。 */
        @Test
        @DisplayName("UT-BR05-01 28 日の月は 160 時間 00 分")
        void february() {
            assertThat(period(2026, 2).statutoryTotalLimit(WEEKLY))
                    .isEqualTo(Duration.ofHours(160));
        }

        @Test
        @DisplayName("UT-BR05-02 31 日の月は 177 時間 08 分（分未満切り捨て）")
        void thirtyOneDays() {
            assertThat(period(2026, 5).statutoryTotalLimit(WEEKLY))
                    .isEqualTo(Duration.ofMinutes(10_628));
        }

        @Test
        @DisplayName("UT-BR05-03 うるう年の 2 月は 165 時間 42 分")
        void leapFebruary() {
            assertThat(period(2028, 2).statutoryTotalLimit(WEEKLY))
                    .isEqualTo(Duration.ofMinutes(9_942));
        }
    }

    @Nested
    @DisplayName("フレックスの時間外と不足")
    class Flex {

        /** 2026-05 は暦日 31 日で総枠 10,628 分。所定は 20 日 × 8 時間 = 9,600 分。 */
        @Test
        @DisplayName("UT-BR05-04 実労働が総枠未満・所定未満なら時間外 0・不足あり")
        void shortageOnly() {
            var may = period(2026, 5);
            // 20 日 × 7 時間 = 8,400 分
            var days = flexDays(YearMonth.of(2026, 5), 20, Duration.ofHours(7));
            weekdaysOnly(YearMonth.of(2026, 5));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.targetWorkingTime()).isEqualTo(Duration.ofMinutes(8_400));
            assertThat(result.overtimeTime()).isZero();
            assertThat(result.shortageTime()).isPositive();
        }

        /**
         * <strong>見落としやすい区間。</strong>
         * 所定総（9,600 分）は超えたが総枠（10,628 分）には届かない。
         * 時間外も不足も 0 になる。
         */
        @Test
        @DisplayName("UT-BR05-05 実労働が所定と総枠の間なら、時間外も不足も 0")
        void betweenScheduledAndLimit() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 20 日 × 8.5 時間 = 10,200 分。9,600 < 10,200 < 10,628
            var days = flexDays(YearMonth.of(2026, 5), 20, Duration.ofMinutes(510));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.targetWorkingTime()).isEqualTo(Duration.ofMinutes(10_200));
            assertThat(result.overtimeTime()).as("総枠内なので時間外 0").isZero();
            assertThat(result.shortageTime()).as("所定を超えているので不足 0").isZero();
        }

        @Test
        @DisplayName("UT-BR05-06 実労働が総枠を超えた分が時間外労働になる")
        void overtimeOnly() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 20 日 × 9 時間 = 10,800 分。総枠 10,628 分を 172 分超える
            var days = flexDays(YearMonth.of(2026, 5), 20, Duration.ofHours(9));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.overtimeTime()).isEqualTo(Duration.ofMinutes(172));
            assertThat(result.shortageTime()).isZero();
        }

        /**
         * <strong>フレックスでは日々 8 時間超でも時間外にならない</strong>（BR-05）。
         * 月の総枠で判定するので、日次・週次の時間外は 0 のままである。
         */
        @Test
        @DisplayName("UT-BR05-07 日々 8 時間超でも月の総枠内なら時間外 0")
        void dailyExcessDoesNotMatter() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 10 日 × 12 時間 = 7,200 分。日々 4 時間ずつ超えているが総枠内
            var days = flexDays(YearMonth.of(2026, 5), 10, Duration.ofHours(12));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.overtimeTime()).isZero();
            assertThat(result.dailyOvertimeTime()).isZero();
            assertThat(result.weeklyOvertimeTime())
                    .as("フレックスに週次判定は適用しない").isZero();
        }

        @Test
        @DisplayName("UT-BR05-16 対象労働時間が総枠ちょうどなら時間外 0")
        void exactlyAtTheLimit() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            var days = List.of(DailyAttendances.flexDay(LocalDate.of(2026, 5, 1),
                    Duration.ofMinutes(10_628)));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.targetWorkingTime()).isEqualTo(result.statutoryTotalLimit());
            assertThat(result.overtimeTime()).isZero();
        }

        /**
         * 対象労働時間が所定総ちょうど。
         * <strong>境界の内側で試すと、比較を {@code <} と {@code <=} のどちらに
         * 書いても通ってしまう。</strong>
         */
        @Test
        @DisplayName("UT-BR05-17 対象労働時間が所定総ちょうどなら不足 0")
        void exactlyAtTheScheduledTotal() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 2026-05 の平日は 21 日。所定総 = 21 × 8 時間 = 10,080 分
            var days = List.of(DailyAttendances.flexDay(LocalDate.of(2026, 5, 1),
                    Duration.ofMinutes(10_080)));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.scheduledTotalTime()).isEqualTo(Duration.ofMinutes(10_080));
            assertThat(result.shortageTime()).isZero();
            assertThat(result.overtimeTime()).as("総枠 10,628 分には届かない").isZero();
        }

        /**
         * 深夜は<strong>労働時間を分割する区分ではなく重なる属性</strong>である（BR-06）。
         * 日次が確定した合計をそのまま積むだけで、対象労働時間には影響しない。
         */
        @Test
        @DisplayName("UT-BR05-09 深夜労働は日次の合計がそのまま計上される")
        void nightIsSummedFromDaily() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            var days = List.of(
                    DailyAttendances.flexNightDay(LocalDate.of(2026, 5, 1),
                            Duration.ofHours(10), Duration.ofHours(2)),
                    DailyAttendances.flexNightDay(LocalDate.of(2026, 5, 4),
                            Duration.ofHours(9), Duration.ofMinutes(30)));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.nightTime()).isEqualTo(Duration.ofMinutes(150));
            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(19));
            assertThat(result.targetWorkingTime())
                    .as("深夜は対象労働時間を増減させない").isEqualTo(Duration.ofHours(19));
        }

        @Test
        @DisplayName("UT-BR05-18 打刻が 1 件も無い月は不足＝所定総になる（例外にしない）")
        void emptyMonth() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));

            var result = calculator.calculate(TARO, may, List.of(), flexRule(),
                    Duration.ZERO);

            assertThat(result.workingTime()).isZero();
            assertThat(result.overtimeTime()).isZero();
            assertThat(result.shortageTime()).isEqualTo(result.scheduledTotalTime());
        }
    }

    @Nested
    @DisplayName("所定総 > 総枠 の月")
    class ScheduledAboveLimit {

        /**
         * <strong>時間外と不足が同時に正になる。</strong>
         * 2026-06 は暦日 30 日で総枠 10,285 分、所定は 22 日 × 8 時間 = 10,560 分。
         * 対象労働 10,400 分はその間に落ちる。
         *
         * <p>時間外は法の上限に対する超過、不足は契約上の約束に対する不足であり、
         * <strong>ものさしが違う。</strong>
         */
        @Test
        @DisplayName("UT-BR05-14 対象労働が所定と総枠の間なら時間外 115 分・不足 160 分")
        void bothArePositive() {
            var june = period(2026, 6);
            weekdaysOnly(YearMonth.of(2026, 6));
            var days = List.of(DailyAttendances.flexDay(LocalDate.of(2026, 6, 1),
                    Duration.ofMinutes(10_400)));

            var result = calculator.calculate(TARO, june, days, flexRule(), Duration.ZERO);

            assertThat(result.statutoryTotalLimit()).isEqualTo(Duration.ofMinutes(10_285));
            assertThat(result.scheduledTotalTime()).isEqualTo(Duration.ofMinutes(10_560));
            assertThat(result.overtimeTime()).isEqualTo(Duration.ofMinutes(115));
            assertThat(result.shortageTime()).isEqualTo(Duration.ofMinutes(160));
        }

        /**
         * 所定総 &lt; 総枠 の月で両方が正なのは計算の誤りである。
         * <strong>「同時に発生しない」を無条件の不変条件にすると適法な月を保存できない</strong>ので、
         * 条件つきの不変条件にしてある。
         */
        @Test
        @DisplayName("UT-BR05-15 所定総 < 総枠 の月で両方が正の値では生成できない")
        void bothPositiveIsRejectedWhenScheduledIsBelowLimit() {
            var may = period(2026, 5);

            assertThatThrownBy(() -> new MonthlySettlement(TARO, may, SERIES,
                    WorkingTimeSystemType.FLEX,
                    Duration.ofMinutes(10_000), Duration.ZERO, Duration.ofMinutes(10_000),
                    Duration.ofMinutes(9_600), Duration.ofMinutes(10_628),
                    Duration.ZERO, Duration.ZERO, Duration.ZERO,
                    Duration.ofMinutes(10), Duration.ofMinutes(10),
                    Duration.ZERO, List.of(), AgreementUsage.of(Duration.ofMinutes(10),
                            Duration.ZERO, Duration.ZERO)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("所定総が法定総枠以下なのに、時間外と不足が同時に");
        }
    }

    @Nested
    @DisplayName("法定休日労働の扱い")
    class LegalHoliday {

        /**
         * <strong>対象労働時間から除く</strong>（BR-07）。
         * 含めると法定休日に働いた分だけ時間外が水増しされ、35% と 25% の二重取りになる。
         */
        @Test
        @DisplayName("UT-BR05-08 法定休日労働は対象労働時間から除かれる")
        void excludedFromTargetTime() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 平日 10,700 分 + 法定休日 480 分。2026-05 の総枠は 10,628 分なので、
            // 除く／除かないで時間外が 72 分と 552 分に分かれる。
            // ★ 閾値をまたぐ数字を選ぶこと。総枠未満の値にすると、
            //   除いても除かなくても時間外 0 で、このテストは何も検査しない
            //   （CLAUDE.md 落とし穴 24・43）。
            var days = List.of(
                    DailyAttendances.flexDay(LocalDate.of(2026, 5, 1),
                            Duration.ofMinutes(10_700)),
                    DailyAttendances.legalHolidayDay(LocalDate.of(2026, 5, 3),
                            Duration.ofHours(8)));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.workingTime()).isEqualTo(Duration.ofMinutes(11_180));
            assertThat(result.legalHolidayTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.targetWorkingTime())
                    .as("法定休日の 480 分を除く").isEqualTo(Duration.ofMinutes(10_700));
            assertThat(result.overtimeTime())
                    .as("除かないと 552 分になってしまう")
                    .isEqualTo(Duration.ofMinutes(10_700 - 10_628));
        }

        /**
         * <strong>法定休日労働は限度時間の対象ではない</strong>（36 条 3 項）。
         * 数えるのは 6 項 2 号の単月 100 時間の方である。
         */
        @Test
        @DisplayName("UT-BR12-11 法定休日労働は限度時間には数えず、単月 100 時間には数える")
        void legalHolidaySplitsAcrossTheTwoLimits() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            var days = List.of(DailyAttendances.legalHolidayDay(LocalDate.of(2026, 5, 3),
                    Duration.ofHours(8)));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.agreementUsage().subjectTime())
                    .as("限度時間の対象は時間外労働だけ").isZero();
            assertThat(result.agreementUsage().combinedTime())
                    .as("6 項の対象には入る").isEqualTo(Duration.ofHours(8));
        }
    }

    @Nested
    @DisplayName("固定時間制")
    class Fixed {

        /**
         * 日次で確定した法定外残業と、週 40 時間超の合計。
         * <strong>フレックスと違い、総枠との比較では判定しない。</strong>
         */
        @Test
        @DisplayName("UT-BR05-20 日次の法定外残業と週 40 時間超が合計される")
        void dailyPlusWeekly() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 5/4(月)〜5/9(土) の 6 日に 9 時間ずつ。
            // 日次: 1 時間 × 6 = 6 時間。法定内は 8 時間 × 6 = 48 時間 → 週 8 時間超
            var days = new ArrayList<DailyAttendance>();
            for (int i = 0; i < 6; i++) {
                days.add(DailyAttendances.fixedDay(LocalDate.of(2026, 5, 4).plusDays(i),
                        Duration.ofHours(9)));
            }

            var result = calculator.calculate(TARO, may, days, fixedRule(), Duration.ZERO);

            assertThat(result.dailyOvertimeTime()).isEqualTo(Duration.ofHours(6));
            assertThat(result.weeklyOvertimeTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeTime()).isEqualTo(Duration.ofHours(14));
        }

        /**
         * <strong>固定時間制では時間外と不足が同時に正になりうる。</strong>
         * 時間外は日次・週次で確定した実績、不足は所定総に対する差であり、
         * <strong>ものさしが違う</strong>ので所定総と総枠の大小とは無関係である。
         *
         * <p>ここを「同時に発生しない」という無条件の不変条件にすると、
         * 忙しい週に残業して別の週に欠勤しただけの正当な月が保存できなくなる
         * （CLAUDE.md 落とし穴 23）。
         */
        @Test
        @DisplayName("UT-BR05-21 固定時間制では所定総 < 総枠 の月でも時間外と不足が同時に正")
        void overtimeAndShortageCoexist() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            var days = new ArrayList<DailyAttendance>();
            for (int i = 0; i < 6; i++) {
                days.add(DailyAttendances.fixedDay(LocalDate.of(2026, 5, 4).plusDays(i),
                        Duration.ofHours(9)));
            }

            var result = calculator.calculate(TARO, may, days, fixedRule(), Duration.ZERO);

            assertThat(result.scheduledTotalTime())
                    .as("所定総 10,080 分 < 総枠 10,628 分")
                    .isLessThan(result.statutoryTotalLimit());
            assertThat(result.overtimeTime()).isEqualTo(Duration.ofHours(14));
            assertThat(result.shortageTime())
                    .as("5/4〜5/8 の平日 5 日は所定を満たす。5/9 は土曜で所定 0。"
                            + "残り 16 日ぶんが不足")
                    .isEqualTo(Duration.ofHours(16 * 8));
        }

        /**
         * <strong>残業で欠勤を埋めない。</strong>
         * 通算の式（所定総 − 対象労働）を当てると不足が 4 時間になり、
         * 4 時間ぶんの欠勤控除が消える。その 4 時間には 25% 割増も支払われているので、
         * 同じ時間が「割増の対象」と「欠勤の穴埋め」に二重に使われることになる。
         */
        @Test
        @DisplayName("UT-BR04-15 固定時間制の不足時間は日ごとの不就労で数える")
        void shortageIsCountedPerDay() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 2026-05 の平日は 21 日。うち 19 日を 8 時間、1 日を 12 時間、1 日は欠勤
            var days = new ArrayList<DailyAttendance>();
            LocalDate date = LocalDate.of(2026, 5, 1);
            int worked = 0;
            while (worked < 20) {
                if (calendar.dayTypeOf(date) == jp.co.sample.kintai.workrule.domain.DayType
                        .WORKDAY) {
                    days.add(DailyAttendances.fixedDay(date,
                            worked == 19 ? Duration.ofHours(12) : Duration.ofHours(8)));
                    worked++;
                }
                date = date.plusDays(1);
            }

            var result = calculator.calculate(TARO, may, days, fixedRule(), Duration.ZERO);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(19 * 8 + 12));
            assertThat(result.shortageTime())
                    .as("欠勤したのは 1 日 = 8 時間。通算の式だと 4 時間になってしまう")
                    .isEqualTo(Duration.ofHours(8));
            assertThat(result.dailyOvertimeTime())
                    .as("12 時間の日の 4 時間は法定外残業のまま").isEqualTo(Duration.ofHours(4));
        }

        @Test
        @DisplayName("UT-BR04-05 フレックスには週 40 時間超を適用しない")
        void flexHasNoWeeklyOvertime() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            var days = new ArrayList<DailyAttendance>();
            for (int i = 0; i < 6; i++) {
                days.add(DailyAttendances.flexDay(LocalDate.of(2026, 5, 4).plusDays(i),
                        Duration.ofHours(9)));
            }

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.weeklyOvertimeTime()).isZero();
            assertThat(result.dailyOvertimeTime()).isZero();
        }
    }

    @Nested
    @DisplayName("月中入社・月中退職")
    class PartialMonth {

        /**
         * <strong>清算期間は暦月ではなく在籍期間との交差である。</strong>
         * 暦月で数えると総枠が 16 日ぶんではなく 30 日ぶんになり、
         * 時間外労働が計上されずに賃金が不足する。
         */
        @Test
        @DisplayName("UT-BR05-11 4/15 入社の初月の総枠は 16 日ぶんの 5,485 分")
        void hiredMidMonth() {
            var employment = DateRange.startingAt(LocalDate.of(2026, 4, 15));
            var april = SettlementPeriod.of(YearMonth.of(2026, 4), employment).orElseThrow();

            assertThat(april.days()).isEqualTo(16);
            assertThat(april.statutoryTotalLimit(WEEKLY))
                    .isEqualTo(Duration.ofMinutes(5_485));
        }

        /**
         * <strong>退職日は最終在籍日</strong>なので、半開区間へは {@code plusDays(1)} で写す。
         * 閉区間のまま扱うと最終日の 1 日が消え、総枠が 1 日ぶん短くなる
         * （CLAUDE.md 落とし穴 10）。
         */
        @Test
        @DisplayName("UT-BR05-12 9/20 退職の最終月の総枠は 20 日ぶんの 6,857 分")
        void retiredMidMonth() {
            var employment = DateRange.closed(LocalDate.of(2020, 4, 1),
                    LocalDate.of(2026, 9, 20));
            var september =
                    SettlementPeriod.of(YearMonth.of(2026, 9), employment).orElseThrow();

            assertThat(september.period().toExclusive())
                    .as("最終在籍日の翌日").isEqualTo(LocalDate.of(2026, 9, 21));
            assertThat(september.days()).isEqualTo(20);
            assertThat(september.statutoryTotalLimit(WEEKLY))
                    .isEqualTo(Duration.ofMinutes(6_857));
        }

        /**
         * <strong>所定総労働時間も清算期間で数える。</strong>
         * 暦月の所定労働日数で数えると、初月の不足時間が水増しされる。
         */
        @Test
        @DisplayName("UT-BR05-13 月中入社の所定総労働時間は清算期間の所定労働日数で数える")
        void scheduledTotalUsesTheSettlementPeriod() {
            var employment = DateRange.startingAt(LocalDate.of(2026, 4, 15));
            var april = SettlementPeriod.of(YearMonth.of(2026, 4), employment).orElseThrow();
            weekdaysOnly(YearMonth.of(2026, 4));

            var result = calculator.calculate(TARO, april, List.of(), flexRule(),
                    Duration.ZERO);

            int workdaysInPeriod = calendar.workdayCountIn(april.period());
            assertThat(workdaysInPeriod)
                    .as("4/15 以降の平日だけ").isLessThan(22);
            assertThat(result.scheduledTotalTime())
                    .isEqualTo(Duration.ofHours(8).multipliedBy(workdaysInPeriod));
        }
    }

    @Nested
    @DisplayName("月 60 時間超の 50% 割増（BR-04 但書）")
    class HighRateOvertime {

        /** 時間外の内訳だけを差し替えた固定時間制の清算結果。 */
        private MonthlySettlement fixedWithOvertime(Duration overtime) {
            var may = period(2026, 5);
            return new MonthlySettlement(TARO, may, SERIES, WorkingTimeSystemType.FIXED,
                    overtime, Duration.ZERO, overtime,
                    Duration.ZERO, Duration.ofMinutes(10_628),
                    overtime, Duration.ZERO, Duration.ZERO, overtime,
                    Duration.ZERO, Duration.ZERO, List.of(),
                    AgreementUsage.of(overtime, Duration.ZERO, Duration.ZERO));
        }

        /**
         * <strong>境界の内側だけで試さない。</strong>
         * ちょうど 60 時間で 0、1 分超えて 1 分になることを両方確かめないと、
         * 比較を {@code <} と {@code <=} のどちらに書いても通ってしまう
         * （CLAUDE.md 落とし穴 24・43）。
         */
        @Test
        @DisplayName("UT-BR04-11 時間外がちょうど 60 時間なら対象 0、1 分超えると 1 分")
        void exactlyAtTheThreshold() {
            assertThat(fixedWithOvertime(Duration.ofHours(60)).overtimeOver60Time())
                    .isZero();
            assertThat(fixedWithOvertime(Duration.ofHours(60)).hasHighRateOvertime())
                    .isFalse();
            assertThat(fixedWithOvertime(Duration.ofMinutes(3_601)).overtimeOver60Time())
                    .isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        @DisplayName("UT-BR04-12 時間外が 70 時間なら 50% の対象は 10 時間")
        void tenHoursOverTheThreshold() {
            var result = fixedWithOvertime(Duration.ofHours(70));

            assertThat(result.overtimeOver60Time()).isEqualTo(Duration.ofHours(10));
            assertThat(result.hasHighRateOvertime()).isTrue();
        }

        /**
         * <strong>法定内残業は時間外労働ではない</strong>ので 60 時間にも数えない。
         * 所定休日は所定 0 なので、8 時間までの労働はすべて法定内残業になる（BR-07）。
         */
        @Test
        @DisplayName("UT-BR04-13 所定休日の法定内残業だけの月は時間外 0")
        void withinStatutoryOvertimeIsNotCounted() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 5/9 は土曜（所定休日）。9:00–18:00 休憩 1 時間 = 実労働 8 時間
            var saturday = realDay(LocalDate.of(2026, 5, 9), WorkRules.fixed());

            var result = calculator.calculate(TARO, may, List.of(saturday), fixedRule(),
                    Duration.ZERO);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.dailyOvertimeTime()).as("8 時間を超えていない").isZero();
            assertThat(result.overtimeTime()).isZero();
            assertThat(result.overtimeOver60Time()).isZero();
        }

        /**
         * <strong>制度で適用の有無は変わらない</strong>（要件定義書 0.5 の BR-05）。
         * 37 条 1 項但書は時間外労働一般に対する規定である。
         */
        @Test
        @DisplayName("UT-BR04-14 フレックスの総枠超過 70 時間も 50% の対象になる")
        void appliesToFlexAsWell() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            // 総枠 10,628 分 + 70 時間（4,200 分）
            var days = List.of(DailyAttendances.flexDay(LocalDate.of(2026, 5, 1),
                    Duration.ofMinutes(10_628 + 4_200)));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.overtimeTime()).isEqualTo(Duration.ofHours(70));
            assertThat(result.overtimeOver60Time()).isEqualTo(Duration.ofHours(10));
        }
    }

    @Nested
    @DisplayName("法定休日から翌暦日への通算（BR-07）")
    class CarryOver {

        /** 5/3 は日曜（法定休日）、5/4〜5/8 は平日、5/9 は土曜。 */
        private static final LocalDate SUNDAY = LocalDate.of(2026, 5, 3);

        /**
         * <strong>通算で法定外になった時間を、週 40 時間の判定からも引く。</strong>
         * 引かないと、同じ 6 時間を通算分としても週 40 時間超としても数える。
         */
        @Test
        @DisplayName("UT-BR07-03 通算で法定外になった分を週 40 時間の法定内から引く")
        void carriedOverTimeIsRemovedFromTheWeeklyBase() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            var days = new ArrayList<DailyAttendance>();
            // 日曜（法定休日）22:00 → 月曜 06:00。0 時以降の 6 時間が月曜へ持ち越される
            days.add(realDay(SUNDAY, Punches.on("2026-05-03").in("22:00")
                    .out("2026-05-04T06:00").build(), WorkRules.fixed()));
            // 月〜金は 9:00–18:00 休憩 1 時間 = 実労働 8 時間 × 5 日 = 40 時間
            for (int i = 0; i < 5; i++) {
                days.add(realDay(LocalDate.of(2026, 5, 4).plusDays(i), WorkRules.fixed()));
            }

            var result = calculator.calculate(TARO, may, days, fixedRule(), Duration.ZERO);

            assertThat(result.carriedOverOvertimeTime())
                    .as("月曜の暦日は 6 + 8 = 14 時間。8 時間超の 6 時間")
                    .isEqualTo(Duration.ofHours(6));
            assertThat(result.dailyOvertimeTime())
                    .as("どの勤務日も 1 日 8 時間は超えていない").isZero();
            assertThat(result.weeklyOvertimeTime())
                    .as("引かないと 46 − 40 = 6 時間を二重に数える").isZero();
            assertThat(result.overtimeTime()).isEqualTo(Duration.ofHours(6));
        }

        /**
         * フレックスでは持ち越し分は既に対象労働時間に入っており、総枠で判定される。
         * 日次の 8 時間で重ねて判定すると二重評価になる。
         */
        @Test
        @DisplayName("UT-BR07-05 フレックスには通算による法定外残業を計上しない")
        void flexHasNoCarryOverOvertime() {
            var may = period(2026, 5);
            weekdaysOnly(YearMonth.of(2026, 5));
            var days = List.of(
                    realDay(SUNDAY, Punches.on("2026-05-03").in("22:00")
                            .out("2026-05-04T06:00").build(), WorkRules.flex()),
                    realDay(LocalDate.of(2026, 5, 4), WorkRules.flex()));

            var result = calculator.calculate(TARO, may, days, flexRule(), Duration.ZERO);

            assertThat(result.carriedOverOvertimeTime()).isZero();
            assertThat(result.dailyOvertimeTime()).isZero();
            assertThat(result.weeklyOvertimeTime()).isZero();
        }
    }

    /** 9:00–18:00（休憩 1 時間）を本番の日次計算に通す。 */
    private DailyAttendance realDay(LocalDate workDate, WorkingTimeSystem system) {
        return realDay(workDate, Punches.on(workDate.toString()).in("09:00")
                .breakFrom("12:00").breakTo("13:00").out("18:00").build(), system);
    }

    /**
     * 打刻を<strong>本番の日次計算に通して</strong>日次勤怠を作る。
     *
     * <p>通算（BR-07）が読むのは区間に付いた割増区分であり、それを決めているのは日次側である。
     * 手で組み立てた日次を渡すと、通算の入口にあたる分類そのものを検査しないテストになる。
     */
    private DailyAttendance realDay(LocalDate workDate,
                                    jp.co.sample.kintai.attendance.domain.TimeClockSequence
                                            punches,
                                    WorkingTimeSystem system) {
        return new DailyAttendanceCalculator(calendar)
                .calculate(workDate, punches, WorkRules.rule(system));
    }

    /** その月の土日を休日として登録する。所定労働日数を現実的にするため。 */
    private void weekdaysOnly(YearMonth month) {
        for (LocalDate d = month.atDay(1); d.isBefore(month.plusMonths(1).atDay(1));
                d = d.plusDays(1)) {
            switch (d.getDayOfWeek()) {
                case SUNDAY -> calendar.legalHoliday(d);
                case SATURDAY -> calendar.nonLegalHoliday(d);
                default -> { }
            }
        }
    }
}
