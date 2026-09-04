package jp.co.sample.kintai.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.PremiumType;
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
 * 機能をまたいだ通しの確認（IT-SCN-13〜19）。
 *
 * <p><strong>各コンテキストの単体テスト・制約テストが通っていても、
 * つないだ瞬間に壊れる箇所がある。</strong>
 * 社員 → 就業規則の適用 → 打刻 → 日次集計 → 保存 を実データで 1 本通し、
 * 境界（時点解決・タイムゾーン・番兵・暦日）が層をまたいでも保たれることを確かめる。
 */
@DisplayName("シナリオ: 社員 → 就業規則 → 打刻 → 日次集計")
class DailyAttendanceScenarioTest extends IntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);
    /** 2026-04-06 は月曜。 */
    private static final LocalDate MON = LocalDate.of(2026, 4, 6);

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

    private EmployeeId taro;
    private WorkRuleSeriesId standard;

    @BeforeEach
    void setUpMasterData() {
        taro = hire("E0001");
        standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(ruleOf(standard, HIRED));
        series.assign(taro, standard, HIRED);
    }

    private EmployeeId hire(String number) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), number + " 太郎",
                new Email(number.toLowerCase() + "@example.com"), HIRED,
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        return id;
    }

    /** 9:00–18:00 / 休憩 60 分 = 所定 8 時間、法定 8 時間。 */
    private static WorkRule ruleOf(WorkRuleSeriesId seriesId, LocalDate validFrom) {
        return WorkRules.versionOf(seriesId, validFrom, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD);
    }

    private void punch(LocalDate workDate, String... times) {
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
    }

    /** 保存された規則で、保存された打刻を計算して保存する。 */
    private DailyAttendance calculateAndSave(LocalDate workDate) {
        WorkRule rule = workRules.findEffective(taro, workDate).orElseThrow();
        var attendance = new DailyAttendanceCalculator(calendar)
                .calculate(workDate, timeClocks.findByWorkDate(taro, workDate), rule);
        dailyAttendances.save(taro, attendance, rule.id());
        return attendance;
    }

    @Nested
    @DisplayName("基本の 1 日")
    class OneDay {

        @Test
        @DisplayName("IT-SCN-13 打刻を保存して読み戻し、定時どおりに集計できる")
        void exactlyScheduled() {
            punch(MON, "2026-04-06T09:00", "2026-04-06T12:00", "2026-04-06T13:00",
                    "2026-04-06T18:00");

            var result = calculateAndSave(MON);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.breakTime()).isEqualTo(Duration.ofHours(1));
            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.overtimeBeyondStatutoryTime()).isZero();
        }

        /**
         * <strong>保存して読み戻しても同じ値になる。</strong>
         * ここが崩れると、画面が表示する勤怠と計算結果が食い違う。
         */
        @Test
        @DisplayName("IT-SCN-14 保存した日次勤怠を読み戻すと同じ値になる")
        void roundTrip() {
            punch(MON, "2026-04-06T09:00", "2026-04-06T12:00", "2026-04-06T13:00",
                    "2026-04-06T20:00");
            var calculated = calculateAndSave(MON);

            assertThat(dailyAttendances.find(taro, MON)).contains(calculated);
        }

        /** 再計算しても内訳が増えない（まるごと入れ替える）。 */
        @Test
        @DisplayName("IT-SCN-15 再計算しても内訳が二重にならない")
        void recalculation() {
            punch(MON, "2026-04-06T09:00", "2026-04-06T18:00");
            calculateAndSave(MON);
            var second = calculateAndSave(MON);

            assertThat(dailyAttendances.find(taro, MON)).contains(second);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM daily_attendance_slices", Integer.class))
                    .isEqualTo(second.slices().size());
        }
    }

    @Nested
    @DisplayName("日をまたぐ勤務")
    class Overnight {

        /**
         * <strong>タイムゾーンの往復がここで効く。</strong>
         * 打刻は {@code timestamptz} で保存され、区間も {@code timestamptz} で保存される。
         * 変換が 1 か所でもずれると、深夜帯 22:00–05:00 の判定が別の時刻で行われる
         * （CLAUDE.md 落とし穴 1）。
         */
        @Test
        @DisplayName("IT-SCN-16 20:00 → 翌 02:00 の勤務で深夜 4 時間が計上される")
        void nightWork() {
            punch(MON, "2026-04-06T20:00", "2026-04-07T02:00");

            var result = calculateAndSave(MON);

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(6));
            assertThat(result.nightTime())
                    .as("22:00–翌 02:00 の 4 時間").isEqualTo(Duration.ofHours(4));
            // 暦日境界で分割されているので、区間は 2 つの暦日に分かれる
            assertThat(result.slices()).extracting(slice -> slice.calendarDate())
                    .contains(MON, MON.plusDays(1));
            assertThat(dailyAttendances.find(taro, MON)).contains(result);
        }

        /** 法定休日は暦日で判定する（BR-07）。勤務日の区分をそのまま当てない。 */
        @Test
        @DisplayName("IT-SCN-17 土曜 22:00 → 日曜 06:00 は日曜分だけが法定休日労働")
        void crossingIntoLegalHoliday() {
            var saturday = LocalDate.of(2026, 4, 4);
            calendar.save(saturday, DayType.NON_LEGAL_HOLIDAY, "所定休日");
            calendar.save(saturday.plusDays(1), DayType.LEGAL_HOLIDAY, "法定休日");
            punch(saturday, "2026-04-04T22:00", "2026-04-05T06:00");

            WorkRule rule = workRules.findEffective(taro, saturday).orElseThrow();
            var result = new DailyAttendanceCalculator(calendar)
                    .calculate(saturday, timeClocks.findByWorkDate(taro, saturday), rule);
            dailyAttendances.save(taro, result, rule.id());

            assertThat(result.workingTime()).isEqualTo(Duration.ofHours(8));
            assertThat(result.legalHolidayTime())
                    .as("日曜 0:00–6:00 の 6 時間だけ").isEqualTo(Duration.ofHours(6));
            assertThat(result.slices())
                    .filteredOn(slice -> slice.has(PremiumType.LEGAL_HOLIDAY)
                            && slice.has(PremiumType.NIGHT))
                    .as("法定休日かつ深夜の区間が保存できる").isNotEmpty();
            assertThat(dailyAttendances.find(taro, saturday)).contains(result);
        }
    }

    @Nested
    @DisplayName("コンテキストをまたいだ取り違え")
    class CrossContextMistakes {

        /**
         * <strong>他人の規則を引かない。</strong>
         * 適用は社員ごとなので、別の社員の適用が混ざっていても
         * その社員の規則だけが返らなければならない（CLAUDE.md 落とし穴 42）。
         */
        @Test
        @DisplayName("IT-SCN-18 別の社員に別の規則を適用しても、互いに影響しない")
        void rulesAreResolvedPerEmployee() {
            var hanako = hire("E0002");
            var flexSeries = new WorkRuleSeriesId(UUID.randomUUID());
            series.save(WorkRuleSeries.active(flexSeries, "フレックス勤務"));
            workRules.save(WorkRules.versionOf(flexSeries, HIRED, WorkRules.flex(),
                    Duration.ofHours(8), NightWindow.STANDARD));
            series.assign(hanako, flexSeries, HIRED);

            assertThat(workRules.findEffective(taro, MON).orElseThrow().systemType().name())
                    .isEqualTo("FIXED");
            assertThat(workRules.findEffective(hanako, MON).orElseThrow().systemType().name())
                    .isEqualTo("FLEX");
        }

        /** 規則が適用されていない社員は空を返す。例外にしない。 */
        @Test
        @DisplayName("規則が適用されていない社員は解決できない")
        void withoutAssignment() {
            var jiro = hire("E0003");

            assertThat(workRules.findEffective(jiro, MON)).isEmpty();
            assertThat(series.findEmployeesWithoutRuleOn(MON)).contains(jiro);
            assertThat(series.findEmployeesWithoutRuleOn(MON))
                    .as("規則が適用されている社員は現れない").doesNotContain(taro);
        }

        /**
         * 勤務日と出勤打刻の日付がずれていると計算できない（BR-03）。
         * <strong>所定だけが別の日のものになる誤りを防ぐ。</strong>
         */
        @Test
        @DisplayName("勤務日と出勤打刻の日付がずれていたら計算できない")
        void workDateMismatch() {
            punch(MON, "2026-04-06T09:00", "2026-04-06T18:00");
            var tuesday = MON.plusDays(1);
            WorkRule rule = workRules.findEffective(taro, tuesday).orElseThrow();
            var punches = timeClocks.findByWorkDate(taro, MON);

            assertThatThrownBy(() -> new DailyAttendanceCalculator(calendar)
                    .calculate(tuesday, punches, rule))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("勤務日と出勤打刻の日付が一致しません");
        }
    }

    @Nested
    @DisplayName("就業規則の改定")
    class Revision {

        /**
         * <strong>改定しても適用行は書き換えない</strong>（ADR 0003）。
         * 社員が指すのは系列なので、版を足すだけで新しい規則に切り替わり、
         * 過去分は当時の版で再計算できる。
         */
        @Test
        @DisplayName("IT-SCN-19 改定の前後で異なる版が解決され、過去分は当時の版のまま")
        void revisionKeepsThePast() {
            var revisedOn = LocalDate.of(2026, 7, 1);
            // 旧版を revisedOn で閉じ、新版（所定 7 時間）を開く
            WorkRule current = workRules.findEffective(taro, MON).orElseThrow();
            workRules.save(new WorkRule(current.id(), current.seriesId(),
                    new DateRange(HIRED, revisedOn), current.workingTimeSystem(),
                    current.statutoryDailyWorkingTime(), current.statutoryWeeklyWorkingTime(),
                    current.nightWindow(), current.premiumRates()));
            workRules.save(WorkRules.versionOf(standard, revisedOn, WorkRules.sevenHours(),
                    Duration.ofHours(8), NightWindow.STANDARD));

            assertThat(workRules.findEffective(taro, MON).orElseThrow().id())
                    .as("改定前は旧版").isEqualTo(current.id());
            assertThat(workRules.findEffective(taro, revisedOn).orElseThrow().id())
                    .as("改定日当日は新版").isNotEqualTo(current.id());

            // 改定後の日で 7 時間 30 分働くと、超過 30 分は法定内残業になる
            punch(revisedOn, "2026-07-01T09:00", "2026-07-01T12:00", "2026-07-01T13:00",
                    "2026-07-01T17:30");
            WorkRule revised = workRules.findEffective(taro, revisedOn).orElseThrow();
            var result = new DailyAttendanceCalculator(calendar)
                    .calculate(revisedOn, timeClocks.findByWorkDate(taro, revisedOn), revised);

            assertThat(result.baseTime()).isEqualTo(Duration.ofHours(7));
            assertThat(result.overtimeWithinStatutoryTime())
                    .isEqualTo(Duration.ofMinutes(30));
        }
    }

    @Nested
    @DisplayName("カレンダーの読み書き")
    class Calendar {

        @Test
        @DisplayName("未登録の日は所定労働日として扱う")
        void unregisteredIsWorkday() {
            assertThat(calendar.dayTypeOf(LocalDate.of(2026, 6, 1)))
                    .isEqualTo(DayType.WORKDAY);
        }

        @Test
        @DisplayName("所定労働日数を実データから数えられる")
        void workdayCount() {
            for (LocalDate d = LocalDate.of(2026, 6, 1);
                    d.isBefore(LocalDate.of(2026, 7, 1)); d = d.plusDays(1)) {
                switch (d.getDayOfWeek()) {
                    case SUNDAY -> calendar.save(d, DayType.LEGAL_HOLIDAY, "法定休日");
                    case SATURDAY -> calendar.save(d, DayType.NON_LEGAL_HOLIDAY, "所定休日");
                    default -> { }
                }
            }

            assertThat(calendar.workdayCountIn(new DateRange(
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1))))
                    .isEqualTo(22);
        }

        /** 期間分をまとめて読む経路（N+1 を避ける）。未登録の日は現れない。 */
        @Test
        @DisplayName("期間分をまとめて読むと、登録した日だけが現れる")
        void findByPeriod() {
            calendar.save(LocalDate.of(2026, 6, 7), DayType.LEGAL_HOLIDAY, "法定休日");

            var june = calendar.findByPeriod(new DateRange(
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)));

            assertThat(june).containsExactly(
                    java.util.Map.entry(LocalDate.of(2026, 6, 7), DayType.LEGAL_HOLIDAY));
        }
    }

    /** 打刻は追記のみ。読み戻した列がそのまま計算に使える。 */
    @Nested
    @DisplayName("打刻の追記")
    class Punches {

        @Test
        @DisplayName("打刻が 1 件も無い日は空の列を返す（欠勤）")
        void noPunches() {
            assertThat(timeClocks.findByWorkDate(taro, MON).isEmpty()).isTrue();
            assertThat(calculateAndSave(MON).workingTime()).isZero();
        }

        /**
         * 取り消された打刻は読み戻さない。
         * 除外を忘れると、訂正したはずの打刻が生きたまま二重に数えられる。
         */
        @Test
        @DisplayName("取り消された打刻は読み戻さない")
        void revokedEventsAreExcluded() {
            punch(MON, "2026-04-06T09:00", "2026-04-06T18:00");
            List<UUID> ids = jdbc.queryForList("""
                    SELECT id FROM time_clock_events
                     WHERE employee_id = ? AND work_date = ? AND event_type = 'CLOCK_OUT'
                    """, UUID.class, taro.value(), MON);
            jdbc.update("""
                    INSERT INTO time_clock_events (id, work_date, employee_id, entry_type,
                            event_type, occurred_at, source, revokes_event_id, reason,
                            recorded_by)
                    VALUES (?, ?, ?, 'REVOCATION', 'CLOCK_OUT',
                            '2026-04-06 18:00:00+09'::timestamptz, 'CORRECTION', ?,
                            '打刻誤り', ?)
                    """, UUID.randomUUID(), MON, taro.value(), ids.get(0), taro.value());

            var sequence = timeClocks.findByWorkDate(taro, MON);

            assertThat(sequence.events()).hasSize(1);
            assertThat(sequence.isClosed()).as("退勤が取り消されたので未完了").isFalse();
        }
    }
}
