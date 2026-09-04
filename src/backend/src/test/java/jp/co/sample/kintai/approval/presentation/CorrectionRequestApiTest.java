package jp.co.sample.kintai.approval.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import jp.co.sample.kintai.attendance.domain.DailyAttendanceCalculator;
import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
import jp.co.sample.kintai.attendance.domain.RecordedTimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventRepository;
import jp.co.sample.kintai.employee.domain.Assignment;
import jp.co.sample.kintai.employee.domain.AssignmentRepository;
import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentCode;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.Managership;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 打刻の訂正申請の API（IT-APV-56〜70）。
 *
 * <p><strong>承認が 5 つの更新を 1 トランザクションで行うことを通しで見る。</strong>
 * 打刻・日次・月次清算・月次勤怠のどれか 1 つでも取り残されると、
 * 直したはずの数字が別の場所に古いまま残る。
 *
 * <p>対象は 2026-04-06（月）。時計は 2026-05-10 に固定してある。
 */
@DisplayName("打刻の訂正申請の API")
class CorrectionRequestApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);
    private static final YearMonth APRIL = YearMonth.of(2026, 4);
    private static final LocalDate TARGET = LocalDate.of(2026, 4, 6);

    @org.springframework.test.context.bean.override.convention.TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(LocalDate.of(2026, 5, 10).atTime(10, 0)
                .atZone(jp.co.sample.kintai.shared.domain.BusinessZone.ID).toInstant(),
                jp.co.sample.kintai.shared.domain.BusinessZone.ID);
    }

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private DepartmentRepository departments;
    @Autowired
    private AssignmentRepository assignments;
    @Autowired
    private ManagershipRepository managerships;
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

    private EmployeeId yamada;
    private EmployeeId manager;
    private EmployeeId hr;

    @BeforeEach
    void setUpOrganization() {
        yamada = hire("E0001", "山田 太郎", Role.EMPLOYEE);
        manager = hire("E0100", "課長 次郎", Role.EMPLOYEE);
        hr = hire("E0900", "人事 花子", Role.EMPLOYEE, Role.HR);

        var sales = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(sales, new DepartmentCode("SALES"), "営業部"));
        assignments.save(Assignment.startingAt(yamada, sales, HIRED));
        assignments.save(Assignment.startingAt(manager, sales, HIRED));
        managerships.save(Managership.startingAt(sales, manager, HIRED));

        var standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(yamada, standard, HIRED);

        for (LocalDate d = APRIL.atDay(1); d.isBefore(APRIL.plusMonths(1).atDay(1));
                d = d.plusDays(1)) {
            switch (d.getDayOfWeek()) {
                case SUNDAY -> calendar.save(d, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> calendar.save(d, DayType.NON_LEGAL_HOLIDAY, "所定休日");
                default -> workedDay(d);
            }
        }
    }

    private EmployeeId hire(String number, String name, Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED,
                Optional.empty(), Set.of(roles)));
        return id;
    }

    /** 9:00 出勤・17:00 退勤（休憩なし）で 1 日を作る。 */
    private void workedDay(LocalDate workDate) {
        timeClocks.append(yamada, workDate,
                new TimeClockEvent.ClockIn(workDate.atTime(9, 0)), yamada);
        timeClocks.append(yamada, workDate,
                new TimeClockEvent.ClockOut(workDate.atTime(17, 0)), yamada);
        recalculate(workDate);
    }

    private void recalculate(LocalDate workDate) {
        var rule = workRules.findEffective(yamada, workDate).orElseThrow();
        dailyAttendances.save(yamada, new DailyAttendanceCalculator(calendar)
                .calculate(workDate, timeClocks.findByWorkDate(yamada, workDate), rule),
                rule.id());
    }

    private RecordedTimeClockEvent clockOutOf(LocalDate workDate) {
        return timeClocks.findRecordedByWorkDate(yamada, workDate).stream()
                .filter(recorded -> recorded.event() instanceof TimeClockEvent.ClockOut)
                .findFirst().orElseThrow();
    }

    private ResultActions requestCorrection(EmployeeId actor, String number, String body,
                                            Role... roles) throws Exception {
        return mockMvc.perform(post("/api/employees/{id}/correction-requests",
                        yamada.value())
                .with(as(actor, number, roles))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 退勤 17:00 を取り消して 19:00 にする申請。 */
    private String replaceClockOutBody() {
        return """
                {"workDate":"2026-04-06","reason":"退勤打刻を押し忘れました",
                 "items":[{"action":"REVOKE","targetEventId":"%s"},
                          {"action":"ADD","eventType":"CLOCK_OUT",
                           "occurredAt":"2026-04-06T19:00:00"}]}
                """.formatted(clockOutOf(TARGET).id().value());
    }

    private String createRequest() throws Exception {
        var response = requestCorrection(yamada, "E0001", replaceClockOutBody(),
                Role.EMPLOYEE).andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                response.getResponse().getContentAsString(), "$.id");
    }

    private ResultActions decide(String id, String action, EmployeeId actor,
                                 String number, String body, Role... roles)
            throws Exception {
        return mockMvc.perform(post("/api/correction-requests/{id}/" + action, id)
                .with(as(actor, number, roles))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long versionOf(String id) {
        return jdbc.queryForObject(
                "SELECT version FROM time_clock_correction_requests WHERE id = ?",
                Long.class, UUID.fromString(id));
    }

    private int workedMinutesOf(LocalDate workDate) {
        return jdbc.queryForObject("""
                SELECT working_minutes FROM daily_attendances
                WHERE employee_id = ? AND work_date = ?
                """, Integer.class, yamada.value(), workDate);
    }

    @Nested
    @DisplayName("申請")
    class Requesting {

        @Test
        @DisplayName("IT-APV-56 本人は訂正を申請できる")
        void request() throws Exception {
            requestCorrection(yamada, "E0001", replaceClockOutBody(), Role.EMPLOYEE)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.workDate").value("2026-04-06"))
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.version").value(1));
        }

        /**
         * <strong>訂正は本人の意思表示である。</strong>
         * 人事でも代理では出せない。出せると「本人が申請していない訂正」が生まれる。
         */
        @Test
        @DisplayName("IT-APV-57 人事でも他人の訂正は申請できない")
        void proxyIsRejected() throws Exception {
            requestCorrection(hr, "E0900", replaceClockOutBody(),
                    Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("IT-APV-58 理由が空白だけだと 400")
        void reasonIsRequired() throws Exception {
            requestCorrection(yamada, "E0001", """
                    {"workDate":"2026-04-06","reason":"  ",
                     "items":[{"action":"ADD","eventType":"BREAK_START",
                               "occurredAt":"2026-04-06T12:00:00"}]}
                    """, Role.EMPLOYEE)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("IT-APV-59 訂正内容が空だと 400")
        void itemsAreRequired() throws Exception {
            requestCorrection(yamada, "E0001",
                    "{\"workDate\":\"2026-04-06\",\"reason\":\"直したい\",\"items\":[]}",
                    Role.EMPLOYEE)
                    .andExpect(status().isBadRequest());
        }

        /** <strong>同一勤務日の未処理は 1 件まで。</strong> 競合する訂正で打刻列が壊れる。 */
        @Test
        @DisplayName("IT-APV-60 同じ勤務日に未処理の申請が 2 件はできない")
        void pendingIsUnique() throws Exception {
            createRequest();

            requestCorrection(yamada, "E0001", replaceClockOutBody(), Role.EMPLOYEE)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:pending-correction-exists"));
        }

        /**
         * <strong>適用すると打刻列が壊れる訂正は、申請の時点で拒む。</strong>
         * 承認したあとで分かるのでは遅い。
         */
        @Test
        @DisplayName("IT-APV-61 適用すると打刻列が壊れる訂正は 422")
        void brokenSequence() throws Exception {
            var clockIn = timeClocks.findRecordedByWorkDate(yamada, TARGET).stream()
                    .filter(r -> r.event() instanceof TimeClockEvent.ClockIn)
                    .findFirst().orElseThrow();

            requestCorrection(yamada, "E0001", """
                    {"workDate":"2026-04-06","reason":"出勤を消したい",
                     "items":[{"action":"REVOKE","targetEventId":"%s"}]}
                    """.formatted(clockIn.id().value()), Role.EMPLOYEE)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-time-clock-sequence"));
        }

        /** <strong>実在しない打刻を指す申請は、外部キー違反ではなく業務エラーにする。</strong> */
        @Test
        @DisplayName("IT-APV-62 実在しない打刻を取消対象にすると 409 で、その ID が返る")
        void missingTarget() throws Exception {
            var stranger = UUID.randomUUID();

            requestCorrection(yamada, "E0001", """
                    {"workDate":"2026-04-06","reason":"直したい",
                     "items":[{"action":"REVOKE","targetEventId":"%s"},
                              {"action":"ADD","eventType":"CLOCK_OUT",
                               "occurredAt":"2026-04-06T19:00:00"}]}
                    """.formatted(stranger), Role.EMPLOYEE)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:correction-target-not-found"))
                    .andExpect(jsonPath("$.missingTargetIds[0]")
                            .value(stranger.toString()));
        }

        /** 打刻漏れの補完は <strong>ADD だけ</strong>で表せる。 */
        @Test
        @DisplayName("IT-APV-63 追加だけの申請も受け付ける")
        void addOnly() throws Exception {
            requestCorrection(yamada, "E0001", """
                    {"workDate":"2026-04-06","reason":"休憩の打刻を忘れました",
                     "items":[{"action":"ADD","eventType":"BREAK_START",
                               "occurredAt":"2026-04-06T12:00:00"},
                              {"action":"ADD","eventType":"BREAK_END",
                               "occurredAt":"2026-04-06T13:00:00"}]}
                    """, Role.EMPLOYEE)
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("承認")
    class Approving {

        /**
         * <strong>承認は 5 つの更新を 1 トランザクションで行う。</strong>
         * 打刻・日次・月次清算・月次勤怠のすべてが動くことを見る。
         */
        @Test
        @DisplayName("IT-APV-64 承認すると打刻が書き換わり、日次が計算し直される")
        void approve() throws Exception {
            assertThat(workedMinutesOf(TARGET)).isEqualTo(8 * 60);
            var id = createRequest();

            decide(id, "approval", manager, "E0100",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("APPROVED"));

            // 17:00 → 19:00 になったので 2 時間増える
            assertThat(workedMinutesOf(TARGET)).isEqualTo(10 * 60);
        }

        /**
         * <strong>元の打刻を消さない。</strong>
         * 取消行を追記することで訂正を表すので、
         * 「元は 17:00 だった」があとから提示できる。
         */
        @Test
        @DisplayName("IT-APV-65 承認しても元の打刻は残る")
        void originalIsKept() throws Exception {
            var original = clockOutOf(TARGET).id().value();
            var id = createRequest();
            decide(id, "approval", manager, "E0100",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER).andExpect(status().isOk());

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM time_clock_events
                    WHERE work_date = ? AND id = ?
                    """, Integer.class, TARGET, original)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM time_clock_events
                    WHERE work_date = ? AND entry_type = 'REVOCATION'
                      AND revokes_event_id = ?
                    """, Integer.class, TARGET, original)).isEqualTo(1);
            // 訂正で足した打刻には理由が残る
            assertThat(jdbc.queryForObject("""
                    SELECT reason FROM time_clock_events
                    WHERE work_date = ? AND source = 'CORRECTION' AND entry_type = 'ENTRY'
                    """, String.class, TARGET)).isEqualTo("退勤打刻を押し忘れました");
        }

        /**
         * <strong>提出済みだった月は下書きに戻る。</strong>
         * 戻さないと、承認者が確認した内容と実際に確定される内容が食い違う。
         */
        @Test
        @DisplayName("IT-APV-66 提出済みの月の訂正を承認すると、月次勤怠が下書きに戻る")
        void revertsMonthlyAttendance() throws Exception {
            mockMvc.perform(post(
                            "/api/employees/{id}/monthly-attendances/{month}/submission",
                            yamada.value(), "2026-04")
                            .with(as(yamada, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isOk());

            var id = createRequest();
            decide(id, "approval", manager, "E0100",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.monthlyAttendanceStatus").value("DRAFT"));

            assertThat(jdbc.queryForObject("""
                    SELECT status FROM monthly_attendances
                    WHERE employee_id = ? AND target_month = '2026-04-01'
                    """, String.class, yamada.value())).isEqualTo("DRAFT");
            // 差戻しと区別できる証跡が残る
            assertThat(jdbc.queryForList("""
                    SELECT event_kind FROM approval_events ORDER BY occurred_at
                    """, String.class)).containsExactly("SUBMIT", "REVERT_BY_CORRECTION");
        }

        /** <strong>月次清算も計算し直す。</strong> 日次だけ直ると月次の時間外が古いまま残る。 */
        @Test
        @DisplayName("IT-APV-67 承認すると月次清算も計算し直される")
        void recalculatesSettlement() throws Exception {
            var id = createRequest();
            decide(id, "approval", manager, "E0100",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER).andExpect(status().isOk());

            // 2 時間増えたぶんが日次の時間外として月次に乗る
            assertThat(jdbc.queryForObject("""
                    SELECT daily_overtime_minutes FROM monthly_settlements
                    WHERE employee_id = ? AND target_month = '2026-04-01'
                    """, Integer.class, yamada.value())).isEqualTo(120);
        }

        @Test
        @DisplayName("IT-APV-68 本人は自分の訂正を承認できない")
        void selfApproval() throws Exception {
            var id = createRequest();

            decide(id, "approval", yamada, "E0001",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());

            assertThat(workedMinutesOf(TARGET)).isEqualTo(8 * 60);
        }

        @Test
        @DisplayName("IT-APV-69 承認者でない社員は承認できない")
        void notTheApprover() throws Exception {
            var outsider = hire("E0003", "他部署 四郎", Role.EMPLOYEE);
            var id = createRequest();

            decide(id, "approval", outsider, "E0003",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());
        }

        /** <strong>古い版での承認は拒否する。</strong> */
        @Test
        @DisplayName("IT-APV-70 古い版で承認すると 409 になり、打刻は変わらない")
        void staleVersion() throws Exception {
            var id = createRequest();

            decide(id, "approval", manager, "E0100", "{\"version\":0}",
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));

            assertThat(workedMinutesOf(TARGET)).isEqualTo(8 * 60);
        }
    }

    @Nested
    @DisplayName("却下と取下げ")
    class RejectAndCancel {

        @Test
        @DisplayName("IT-APV-71 却下は理由が必須")
        void rejectionRequiresReason() throws Exception {
            var id = createRequest();

            decide(id, "rejection", manager, "E0100",
                    "{\"reason\":\"  \",\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isBadRequest());

            decide(id, "rejection", manager, "E0100",
                    "{\"reason\":\"打刻の記録と合いません\",\"version\":%d}"
                            .formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REJECTED"));

            // 却下では打刻を書き換えない
            assertThat(workedMinutesOf(TARGET)).isEqualTo(8 * 60);
        }

        /**
         * <strong>取下げが無いと、正しい申請を出し直せない。</strong>
         * 同一勤務日の未処理は 1 件までなので、
         * 承認者が却下するまで待つことになる。
         */
        @Test
        @DisplayName("IT-APV-72 本人は取り下げられ、同じ勤務日に出し直せる")
        void cancelAndResubmit() throws Exception {
            var id = createRequest();

            decide(id, "cancellation", yamada, "E0001",
                    "{\"version\":%d}".formatted(versionOf(id)), Role.EMPLOYEE)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELED"));

            requestCorrection(yamada, "E0001", replaceClockOutBody(), Role.EMPLOYEE)
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("IT-APV-73 他人は取り下げられない")
        void cancelByOther() throws Exception {
            var id = createRequest();

            decide(id, "cancellation", manager, "E0100",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("IT-APV-74 決着済みの申請は再び決裁できない")
        void alreadyDecided() throws Exception {
            var id = createRequest();
            decide(id, "approval", manager, "E0100",
                    "{\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER).andExpect(status().isOk());

            decide(id, "rejection", manager, "E0100",
                    "{\"reason\":\"やっぱりだめ\",\"version\":%d}".formatted(versionOf(id)),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:correction-already-decided"));
        }
    }

    @Nested
    @DisplayName("月の状態")
    class MonthState {

        /**
         * <strong>締め済みと承認済みを分ける。</strong>
         * 承認済みは承認を取り消せば直せるので、利用者への案内がまったく違う。
         */
        @Test
        @DisplayName("IT-APV-75 承認済みの月は month-not-editable で拒否される")
        void approvedMonth() throws Exception {
            submitAndApprove();

            requestCorrection(yamada, "E0001", replaceClockOutBody(), Role.EMPLOYEE)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-not-editable"));
        }

        @Test
        @DisplayName("IT-APV-76 締め済みの月は month-already-closed で拒否される")
        void closedMonth() throws Exception {
            submitAndApprove();
            transition("closure", hr, "E0900", Role.EMPLOYEE, Role.HR);

            requestCorrection(yamada, "E0001", replaceClockOutBody(), Role.EMPLOYEE)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-already-closed"));
        }

        /** 提出済みの月は<strong>訂正申請を受け付ける</strong>（直接の打刻は受け付けない）。 */
        @Test
        @DisplayName("IT-APV-77 提出済みの月には訂正を申請できる")
        void submittedMonth() throws Exception {
            transition("submission", yamada, "E0001", Role.EMPLOYEE);

            requestCorrection(yamada, "E0001", replaceClockOutBody(), Role.EMPLOYEE)
                    .andExpect(status().isCreated());
        }

        private void submitAndApprove() throws Exception {
            transition("submission", yamada, "E0001", Role.EMPLOYEE);
            transition("approval", manager, "E0100", Role.EMPLOYEE, Role.APPROVER);
        }

        private void transition(String action, EmployeeId actor, String number,
                                Role... roles) throws Exception {
            long version = jdbc.queryForList("""
                    SELECT version FROM monthly_attendances
                    WHERE employee_id = ? AND target_month = '2026-04-01'
                    """, Long.class, yamada.value()).stream().findFirst().orElse(0L);
            mockMvc.perform(post(
                            "/api/employees/{id}/monthly-attendances/{month}/" + action,
                            yamada.value(), "2026-04")
                            .with(as(actor, number, roles))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":%d}".formatted(version)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("参照")
    class Reading {

        @Test
        @DisplayName("IT-APV-78 承認待ち一覧は見てよい社員のぶんだけ返る")
        void pendingApproval() throws Exception {
            createRequest();

            mockMvc.perform(get("/api/correction-requests/pending-approval")
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));

            var outsider = hire("E0003", "他部署 四郎", Role.EMPLOYEE);
            mockMvc.perform(get("/api/correction-requests/pending-approval")
                            .with(as(outsider, "E0003", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        /**
         * <strong>決着したものも含めて返す。</strong>
         * 承認待ちだけを返すと、却下された申請が画面から消えて理由が読めなくなる。
         */
        @Test
        @DisplayName("IT-APV-80 その社員の申請一覧は、決着したものも含む")
        void listOfEmployee() throws Exception {
            var id = createRequest();
            decide(id, "cancellation", yamada, "E0001",
                    "{\"version\":%d}".formatted(versionOf(id)), Role.EMPLOYEE)
                    .andExpect(status().isOk());
            createRequest();

            mockMvc.perform(get("/api/employees/{id}/correction-requests",
                            yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[?(@.status=='CANCELED')]").exists())
                    .andExpect(jsonPath("$[?(@.status=='SUBMITTED')]").exists());
        }

        @Test
        @DisplayName("IT-APV-79 申請を 1 件取得できる")
        void getOne() throws Exception {
            var id = createRequest();

            mockMvc.perform(get("/api/correction-requests/{id}", id)
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.version").value(1));
        }
    }
}
