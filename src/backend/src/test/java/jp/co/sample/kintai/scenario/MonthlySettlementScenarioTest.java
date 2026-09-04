package jp.co.sample.kintai.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.IntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 機能をまたいだ通しの確認（IT-SCN-20〜25）。
 *
 * <p>日次までは {@code DailyAttendanceScenarioTest} が通している。
 * ここは<strong>その先</strong>、すなわち
 * 日次勤怠 → 走査範囲での読み出し → 月次清算 → 保存 → 読み戻し を実データで 1 本通す。
 *
 * <p>単体テストは日次勤怠を組み立てて渡すので、
 * <strong>「実際に DB から読んだ日次で計算できるか」を検査していない。</strong>
 * 走査範囲・暦日の通算・週の帰属は、読み出しの範囲を 1 日間違えるだけで壊れる。
 */
@DisplayName("シナリオ: 日次勤怠 → 月次清算 → 保存")
class MonthlySettlementScenarioTest extends IntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);
    private static final YearMonth MAY = YearMonth.of(2026, 5);

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private WorkRuleSeriesRepository series;
    @Autowired
    private WorkRuleRepository workRules;
    @Autowired
    private CompanyCalendarRepository calendar;
    @Autowired
    private TimeClockEventRepository timeClocks;
    @Autowired
    private DailyAttendanceRepository dailyAttendances;
    @Autowired
    private MonthlySettlementService settlements;

    private EmployeeId taro;
    private WorkRuleSeriesId standard;

    @BeforeEach
    void setUpMasterData() {
        taro = hire("E0001", HIRED);
        standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(taro, standard, HIRED);
        registerWeekends(YearMonth.of(2026, 4));
        registerWeekends(MAY);
    }

    private EmployeeId hire(String number, LocalDate hiredOn) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), number + " 太郎",
                new Email(number.toLowerCase() + "@example.com"), hiredOn,
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        return id;
    }

    /** 土日を休日として登録する。日曜が法定休日、土曜が所定休日。 */
    private void registerWeekends(YearMonth month) {
        for (LocalDate d = month.atDay(1); d.isBefore(month.plusMonths(1).atDay(1));
                d = d.plusDays(1)) {
            switch (d.getDayOfWeek()) {
                case SUNDAY -> calendar.save(d, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> calendar.save(d, DayType.NON_LEGAL_HOLIDAY, "所定休日");
                default -> { }
            }
        }
    }

    /** 打刻を保存し、保存された規則で日次を計算して保存する。 */
    private void workedDay(LocalDate workDate, String... times) {
        for (int i = 0; i < times.length; i++) {
            LocalDateTime at = LocalDateTime.parse(times[i]);
            TimeClockEvent event = switch (i) {
                case 0 -> new TimeClockEvent.ClockIn(at);
                default -> i == times.length - 1
                        ? new TimeClockEvent.ClockOut(at)
                        : (i % 2 == 1 ? new TimeClockEvent.BreakStart(at)
                                      : new TimeClockEvent.BreakEnd(at));
            };
            timeClocks.append(taro, workDate, event, taro);
        }
        WorkRule rule = workRules.findEffective(taro, workDate).orElseThrow();
        var attendance = new DailyAttendanceCalculator(calendar)
                .calculate(workDate, timeClocks.findByWorkDate(taro, workDate), rule);
        dailyAttendances.save(taro, attendance, rule.id());
    }

    /** 9:00–18:00（休憩 1 時間）= 実労働 8 時間。 */
    private void regularDay(LocalDate workDate) {
        workedDay(workDate, workDate + "T09:00", workDate + "T12:00",
                workDate + "T13:00", workDate + "T18:00");
    }

    @Nested
    @DisplayName("通しの清算")
    class RoundTrip {

        @Test
        @DisplayName("IT-SCN-20 日次勤怠を清算して保存し、読み戻すと同じ値になる")
        void settleAndReadBack() {
            // 5/4(月)〜5/8(金) を定時どおり
            for (int i = 0; i < 5; i++) {
                regularDay(LocalDate.of(2026, 5, 4).plusDays(i));
            }

            MonthlySettlement calculated = settlements.settle(taro, MAY);

            assertThat(calculated.workingTime()).isEqualTo(Duration.ofHours(40));
            assertThat(calculated.overtimeTime()).isZero();
            assertThat(calculated.shortageTime())
                    .as("2026-05 の所定労働日は 21 日。40 時間しか働いていない")
                    .isEqualTo(Duration.ofHours(21 * 8 - 40));
            assertThat(settlements.find(taro, MAY)).contains(calculated);
        }

        /**
         * <strong>固定時間制でも不足時間は保存できる。</strong>
         * DDL 第 1 版は {@code FIXED AND shortage_minutes = 0} を制約にしており、
         * この行を保存できなかった（IT-SET-04）。
         * <strong>単体テストが通っていても、保存の段で落ちる</strong>形の欠陥である。
         */
        @Test
        @DisplayName("固定時間制で不足のある月を保存できる")
        void fixedWithShortageIsPersisted() {
            regularDay(LocalDate.of(2026, 5, 4));

            settlements.settle(taro, MAY);

            assertThat(jdbc.queryForObject("""
                    SELECT shortage_minutes FROM monthly_settlements
                    """, Integer.class)).isPositive();
        }

        /** 再計算しても週の内訳が増えない（まるごと入れ替える）。 */
        @Test
        @DisplayName("IT-SCN-23 同じ月を再計算しても週の内訳が二重にならない")
        void recalculation() {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);
            MonthlySettlement second = settlements.settle(taro, MAY);

            assertThat(settlements.find(taro, MAY)).contains(second);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM weekly_overtimes", Integer.class))
                    .isEqualTo(second.weeklyBreakdown().size());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM monthly_settlements", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("読み出しの範囲")
    class ScanRange {

        /**
         * <strong>月初の週は前月の日を含む。</strong>
         * 対象月の中だけを読むと、この週の法定内労働が 16 時間に見え、
         * <strong>週 40 時間超を取りこぼす。</strong>
         */
        @Test
        @DisplayName("IT-SCN-21 月初の週に前月の日を含めて週 40 時間超を判定する")
        void weekAtTheStartOfTheMonthIncludesThePreviousMonth() {
            // 4/26(日) から始まる週。4/27(月)〜4/30(木) の 32 時間は前月
            for (int i = 0; i < 4; i++) {
                regularDay(LocalDate.of(2026, 4, 27).plusDays(i));
            }
            regularDay(LocalDate.of(2026, 5, 1));   // 金
            regularDay(LocalDate.of(2026, 5, 2));   // 土（所定休日）

            MonthlySettlement result = settlements.settle(taro, MAY);

            assertThat(result.workingTime())
                    .as("集計は対象月の中だけ").isEqualTo(Duration.ofHours(16));
            assertThat(result.weeklyOvertimeTime())
                    .as("週の法定内は 32 + 8 + 8 = 48 時間。40 時間を 8 時間超える")
                    .isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeTime()).isEqualTo(Duration.ofHours(8));
        }
    }

    @Nested
    @DisplayName("法定休日からの通算（BR-07）")
    class CarryOver {

        /**
         * 法定休日の労働が翌日 0 時以降に及んだ部分は休日労働ではない
         * （昭 63.3.14 基発 150 号）。同じ暦日の通常シフトと通算する。
         */
        @Test
        @DisplayName("IT-SCN-22 法定休日 22:00 → 翌 06:00 が翌暦日の勤務と通算される")
        void carriesOverIntoTheNextCalendarDay() {
            // 5/3 は日曜（法定休日）
            workedDay(LocalDate.of(2026, 5, 3), "2026-05-03T22:00", "2026-05-04T06:00");
            regularDay(LocalDate.of(2026, 5, 4));

            MonthlySettlement result = settlements.settle(taro, MAY);

            assertThat(result.legalHolidayTime())
                    .as("5/3 の 22:00–24:00 だけが休日労働").isEqualTo(Duration.ofHours(2));
            assertThat(result.carriedOverOvertimeTime())
                    .as("月曜の暦日は 6 + 8 = 14 時間。8 時間超の 6 時間")
                    .isEqualTo(Duration.ofHours(6));
            assertThat(result.weeklyOvertimeTime())
                    .as("通算で法定外になった分は週 40 時間の判定から除く").isZero();
            assertThat(result.overtimeTime()).isEqualTo(Duration.ofHours(6));

            assertThat(jdbc.queryForObject("""
                    SELECT carried_over_overtime_minutes FROM monthly_settlements
                    """, Integer.class)).isEqualTo(360);
            assertThat(settlements.find(taro, MAY)).contains(result);
        }
    }

    @Nested
    @DisplayName("36 協定の年度（BR-12）")
    class FiscalYear {

        /**
         * <strong>年度をまたぐと 0 から数え直す。</strong>
         * 暦年で数えると 1 月に上限がリセットされ、
         * 年 360 時間の上限が実質 15 か月ぶんになる。
         */
        @Test
        @DisplayName("IT-SCN-24 年度累計に前年度（3 月）の月を数えない")
        void annualUsageStartsAtTheFiscalYear() {
            registerWeekends(YearMonth.of(2026, 3));
            // 3/2(月) と 4/6(月) に 9:00–20:00（休憩 1 時間）= 10 時間 → 各 2 時間の法定外残業
            overtimeDay(LocalDate.of(2026, 3, 2));
            overtimeDay(LocalDate.of(2026, 4, 6));
            settlements.settle(taro, YearMonth.of(2026, 3));
            settlements.settle(taro, YearMonth.of(2026, 4));

            MonthlySettlement may = settlements.settle(taro, MAY);

            assertThat(may.agreementUsage().annualUsedBefore())
                    .as("2026 年度は 4/1 起算。3 月は前年度なので数えない")
                    .isEqualTo(Duration.ofHours(2));
        }

        private void overtimeDay(LocalDate workDate) {
            workedDay(workDate, workDate + "T09:00", workDate + "T12:00",
                    workDate + "T13:00", workDate + "T20:00");
        }
    }

    @Nested
    @DisplayName("清算できない月")
    class NotSettleable {

        /**
         * <strong>業務エラーとして返す。</strong>
         * {@code IllegalStateException} などにすると 500 になり、
         * 人事には「システムが壊れた」としか見えない。
         */
        @Test
        @DisplayName("IT-SCN-25 在籍していない月の清算は業務エラーになる")
        void beforeHire() {
            assertThatThrownBy(() -> settlements.settle(taro, YearMonth.of(2025, 12)))
                    .isInstanceOf(MonthlySettlementService.NotEmployedInMonthException.class)
                    .hasMessageContaining("対象月に在籍していません");
        }
    }
}
