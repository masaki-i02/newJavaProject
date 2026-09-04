package jp.co.sample.kintai.attendance.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 月次清算の API（IT-API-20〜29）。
 *
 * <p>再計算は 4 つの検査を通る。締め済み・版の不一致・未計算の日・制度の月中変更。
 * <strong>どれも「計算が正しいか」ではなく「計算してよいか」の検査である。</strong>
 */
@DisplayName("月次清算の API")
class MonthlySettlementApiTest extends WebIntegrationTestBase {

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
    private EmployeeId hr;

    @BeforeEach
    void setUpMasterData() {
        taro = hire("E0001", "山田 太郎", Role.EMPLOYEE);
        hr = hire("E0900", "人事 花子", Role.EMPLOYEE, Role.HR);
        var standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(taro, standard, HIRED);
        registerWeekends(YearMonth.of(2026, 4));
        registerWeekends(MAY);
    }

    private EmployeeId hire(String number, String name, Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED, Optional.empty(),
                Set.of(roles)));
        return id;
    }

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

    /** 打刻して日次を計算・保存する。9:00–18:00（休憩 1 時間）= 8 時間。 */
    private void regularDay(LocalDate workDate) {
        punch(workDate, new TimeClockEvent.ClockIn(workDate.atTime(9, 0)));
        punch(workDate, new TimeClockEvent.BreakStart(workDate.atTime(12, 0)));
        punch(workDate, new TimeClockEvent.BreakEnd(workDate.atTime(13, 0)));
        punch(workDate, new TimeClockEvent.ClockOut(workDate.atTime(18, 0)));
        calculate(workDate);
    }

    private void punch(LocalDate workDate, TimeClockEvent event) {
        timeClocks.append(taro, workDate, event, taro);
    }

    private void calculate(LocalDate workDate) {
        WorkRule rule = workRules.findEffective(taro, workDate).orElseThrow();
        dailyAttendances.save(taro, new DailyAttendanceCalculator(calendar)
                .calculate(workDate, timeClocks.findByWorkDate(taro, workDate), rule),
                rule.id());
    }

    private void closeMonth(YearMonth month) {
        LocalDateTime at = LocalDateTime.of(2026, 6, 1, 10, 0);
        jdbc.update("""
                INSERT INTO monthly_attendances (id, employee_id, target_month, status,
                        submitted_at, submitted_by, approved_by, approved_at,
                        closed_by, closed_at)
                VALUES (?, ?, ?, 'CLOSED', ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), taro.value(), month.atDay(1),
                at, taro.value(), hr.value(), at, hr.value(), at);
    }

    @Nested
    @DisplayName("参照")
    class Get {

        @Test
        @DisplayName("IT-API-20 計算済みの月次清算を返す")
        void returnsSettlement() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);

            mockMvc.perform(get("/api/employees/{id}/settlements/{month}",
                            taro.value(), "2026-05").with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.month").value("2026-05"))
                    .andExpect(jsonPath("$.workingTimeSystem").value("FIXED"))
                    .andExpect(jsonPath("$.workingMinutes").value(480))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.period.from").value("2026-05-01"))
                    .andExpect(jsonPath("$.period.toExclusive").value("2026-06-01"))
                    .andExpect(jsonPath("$.agreement.subjectMinutes").value(0));
        }

        /**
         * <strong>制度によって意味を持つ項目が違う。</strong>
         * 固定時間制には週の内訳があり、フレックスには無い。
         * 0 を返すと「0 時間だった」と読めるので、項目ごと落とす。
         */
        @Test
        @DisplayName("IT-API-21 固定時間制は週の内訳を返す")
        void fixedIncludesWeeklyBreakdown() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);

            mockMvc.perform(get("/api/employees/{id}/settlements/{month}",
                            taro.value(), "2026-05").with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dailyOvertimeMinutes").value(0))
                    .andExpect(jsonPath("$.weeklyBreakdown").isArray())
                    .andExpect(jsonPath("$.weeklyBreakdown[0].weekStart").value("2026-05-03"))
                    .andExpect(jsonPath("$.weeklyBreakdown[0].weekEnd").value("2026-05-09"));
        }

        @Test
        @DisplayName("IT-API-22 まだ計算していない月は 404")
        void notCalculatedYet() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/settlements/{month}",
                            taro.value(), "2026-05").with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:resource-not-found"));
        }

        @Test
        @DisplayName("IT-API-23 一般社員は他人の月次清算を見られない")
        void otherEmployee() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);

            mockMvc.perform(get("/api/employees/{id}/settlements/{month}",
                            taro.value(), "2026-05").with(as(hr, "E0900", Role.EMPLOYEE)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("再計算")
    class Recalculate {

        private org.springframework.test.web.servlet.ResultActions recalculate(
                EmployeeId requester, String number, long version, Role... roles)
                throws Exception {
            return mockMvc.perform(
                    post("/api/employees/{id}/settlements/{month}/recalculation",
                            taro.value(), "2026-05")
                            .with(as(requester, number, roles))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":%d}".formatted(version)));
        }

        @Test
        @DisplayName("IT-API-24 人事は再計算でき、版が 1 つ上がる")
        void hrRecalculates() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);

            recalculate(hr, "E0900", 1, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.workingMinutes").value(480));
        }

        @Test
        @DisplayName("IT-API-25 人事でなければ再計算できない")
        void notHr() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);

            recalculate(taro, "E0001", 1, Role.EMPLOYEE)
                    .andExpect(status().isForbidden());
        }

        /**
         * <strong>画面が見ていた版と食い違ったら上書きしない。</strong>
         * その間に別の経路で値が変わっていたら、
         * 人事が見ていない結果を上書きすることになる。
         */
        @Test
        @DisplayName("IT-API-26 版が一致しないと 409")
        void versionMismatch() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);

            recalculate(hr, "E0900", 99, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));
        }

        @Test
        @DisplayName("IT-API-27 締め済みの月は再計算できない")
        void alreadyClosed() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);
            closeMonth(MAY);

            recalculate(hr, "E0900", 1, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-already-closed"));
        }

        /**
         * <strong>どの日が欠けているかを返す。</strong>
         * 月次清算は全日の合計で成り立つので、1 日でも欠けると結果が過少になる。
         * 「未計算の日があります」だけでは、利用者はどこを直せばよいか分からない。
         */
        @Test
        @DisplayName("IT-API-28 未計算の勤務日があると 409 で、その日付を返す")
        void incompleteDays() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);
            // 5/12 は出勤したまま退勤していないので日次が計算されない
            punch(LocalDate.of(2026, 5, 12),
                    new TimeClockEvent.ClockIn(LocalDate.of(2026, 5, 12).atTime(9, 0)));

            recalculate(hr, "E0900", 1, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:daily-attendance-incomplete"))
                    .andExpect(jsonPath("$.incompleteDates[0]").value("2026-05-12"));
        }

        /**
         * <strong>未計算の日を探す範囲は清算期間より広い。</strong>
         * 月初の週は前月の日を含むので、清算期間の中だけを見ると
         * 前月の日が欠けたまま週 40 時間超を判定してしまう。
         */
        @Test
        @DisplayName("IT-API-29 走査範囲に入る前月の未計算日も検出する")
        void incompleteDaysInThePreviousMonth() throws Exception {
            regularDay(LocalDate.of(2026, 5, 4));
            settlements.settle(taro, MAY);
            // 2026-05 の走査範囲は 4/26(日) から。4/27 の退勤が無い
            punch(LocalDate.of(2026, 4, 27),
                    new TimeClockEvent.ClockIn(LocalDate.of(2026, 4, 27).atTime(9, 0)));

            recalculate(hr, "E0900", 1, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.incompleteDates[0]").value("2026-04-27"));
        }
    }

    @Nested
    @DisplayName("締め済みの月への打刻")
    class ClosedMonth {

        /**
         * <strong>締め後は打刻できない</strong>（BR-10）。
         * 状態が変われば通るので 409 であって、権限の 403 ではない。
         */
        @Test
        @DisplayName("IT-API-30 締め済みの月には打刻できない")
        void cannotPunch() throws Exception {
            closeMonth(MAY);

            mockMvc.perform(post("/api/employees/{id}/time-clocks", taro.value())
                            .with(as(taro, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"CLOCK_IN\","
                                    + "\"occurredAt\":\"2026-05-11T09:00:00\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:month-not-open"));
        }

        @Test
        @DisplayName("IT-API-31 締めていない月には打刻できる")
        void canPunchInOpenMonth() throws Exception {
            closeMonth(MAY);

            mockMvc.perform(post("/api/employees/{id}/time-clocks", taro.value())
                            .with(as(taro, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"CLOCK_IN\","
                                    + "\"occurredAt\":\"2026-06-01T09:00:00\"}"))
                    .andExpect(status().isCreated());
        }
    }
}
