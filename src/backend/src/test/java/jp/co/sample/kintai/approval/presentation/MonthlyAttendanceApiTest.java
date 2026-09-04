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
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 提出 → 承認 → 締め の API（IT-APV-30〜44）。
 *
 * <p><strong>「戻せない」ことを確かめるのがこのテストの中心である。</strong>
 * 締め済みからの遷移は型として定義していないので、
 * API から要求しても状態が動かないことを通しで見る。
 *
 * <p>対象月は 2026-04。時計を 2026-05-10 に固定してあるので、
 * 「対象月の末日が到来していること」は満たされる。
 */
@DisplayName("提出・承認・締めの API")
class MonthlyAttendanceApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);
    private static final YearMonth APRIL = YearMonth.of(2026, 4);

    /**
     * 時計を固定する。
     *
     * <p><strong>「対象月の末日が到来しているか」を検査するので、
     * 実際の今日に依存させられない。</strong>
     * 実時刻で回すと、テストが通るかどうかが実行した日で変わる。
     */
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
        yamada = hire("E0001", "山田 太郎", Optional.empty(), Role.EMPLOYEE);
        manager = hire("E0100", "課長 次郎", Optional.empty(), Role.EMPLOYEE);
        hr = hire("E0900", "人事 花子", Optional.empty(), Role.EMPLOYEE, Role.HR);

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

        // 4 月の土日を休日にし、平日はすべて計算済みにする
        for (LocalDate d = APRIL.atDay(1); d.isBefore(APRIL.plusMonths(1).atDay(1));
                d = d.plusDays(1)) {
            switch (d.getDayOfWeek()) {
                case SUNDAY -> calendar.save(d, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> calendar.save(d, DayType.NON_LEGAL_HOLIDAY, "所定休日");
                default -> workedDay(d);
            }
        }
    }

    private EmployeeId hire(String number, String name, Optional<LocalDate> retiredOn,
                           Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED, retiredOn,
                Set.of(roles)));
        return id;
    }

    private void workedDay(LocalDate workDate) {
        timeClocks.append(yamada, workDate,
                new TimeClockEvent.ClockIn(workDate.atTime(9, 0)), yamada);
        timeClocks.append(yamada, workDate,
                new TimeClockEvent.ClockOut(workDate.atTime(17, 0)), yamada);
        WorkRule rule = workRules.findEffective(yamada, workDate).orElseThrow();
        dailyAttendances.save(yamada, new DailyAttendanceCalculator(calendar)
                .calculate(workDate, timeClocks.findByWorkDate(yamada, workDate), rule),
                rule.id());
    }

    /**
     * 遷移を要求する。<strong>現在の版を自動で載せる。</strong>
     *
     * <p>画面は {@code GET} で版を得てから {@code POST} する（API設計書 1.1）ので、
     * ここでもその通りに振る舞わせる。
     * 版が食い違う場合は {@link #transition(String, EmployeeId, String, String, long, Role...)}
     * で明示する。
     */
    private ResultActions transition(String action, EmployeeId actor, String number,
                                     String body, Role... roles) throws Exception {
        return transition(action, actor, number, body, currentVersion(), roles);
    }

    private ResultActions transition(String action, EmployeeId actor, String number,
                                     String body, long version, Role... roles)
            throws Exception {
        var request = post("/api/employees/{id}/monthly-attendances/{month}/" + action,
                yamada.value(), "2026-04").with(as(actor, number, roles));
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                .content(withVersion(body, version)));
    }

    /** 本文へ {@code version} を差し込む。本文が無ければ版だけの本文にする。 */
    private static String withVersion(String body, long version) {
        if (body == null || body.isBlank()) {
            return "{\"version\":%d}".formatted(version);
        }
        return body.replaceFirst("\\}\\s*$", ",\"version\":%d}".formatted(version));
    }

    private long currentVersion() {
        List<Long> found = jdbc.queryForList("""
                SELECT version FROM monthly_attendances
                WHERE employee_id = ? AND target_month = '2026-04-01'
                """, Long.class, yamada.value());
        return found.isEmpty() ? 0L : found.getFirst();
    }

    private void submitAndApprove() throws Exception {
        transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                .andExpect(status().isOk());
        transition("approval", manager, "E0100", null, Role.EMPLOYEE, Role.APPROVER)
                .andExpect(status().isOk());
    }

    private String statusOf() {
        return jdbc.queryForObject("""
                SELECT status FROM monthly_attendances
                WHERE employee_id = ? AND target_month = '2026-04-01'
                """, String.class, yamada.value());
    }

    @Nested
    @DisplayName("通しの遷移")
    class HappyPath {

        @Test
        @DisplayName("IT-APV-30 提出 → 承認 → 締め まで通る")
        void submitApproveClose() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUBMITTED"));

            transition("approval", manager, "E0100", null, Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            transition("closure", hr, "E0900", null, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }

        /** 遷移はすべて証跡に残る。<strong>どの遷移だったかも残す。</strong> */
        @Test
        @DisplayName("IT-APV-31 遷移が監査証跡に残る")
        void auditTrail() throws Exception {
            submitAndApprove();
            transition("closure", hr, "E0900", null, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk());

            assertThat(jdbc.queryForList("""
                    SELECT event_kind FROM approval_events ORDER BY occurred_at, created_at
                    """, String.class))
                    .containsExactly("SUBMIT", "APPROVE", "CLOSE");
        }

        @Test
        @DisplayName("IT-APV-32 何も起きていない月は DRAFT を返す（404 にしない）")
        void draftWhenNothingHappened() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/monthly-attendances/{month}",
                            yamada.value(), "2026-04")
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }
    }

    @Nested
    @DisplayName("戻せないこと")
    class NoWayBack {

        /**
         * <strong>締め済みからの遷移は定義していない。</strong>
         * 型として存在しないので、API から要求しても状態は動かない。
         */
        @Test
        @DisplayName("IT-APV-33 締め済みの月は承認の取消もできない")
        void closedCannotBeRevoked() throws Exception {
            submitAndApprove();
            transition("closure", hr, "E0900", null, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk());

            transition("approval-revocation", hr, "E0900",
                    "{\"reason\":\"やっぱり戻したい\"}", Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-attendance-transition"));
            assertThat(statusOf()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("IT-APV-34 下書きの月は承認できない")
        void draftCannotBeApproved() throws Exception {
            transition("approval", manager, "E0100", null, Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("IT-APV-35 二重提出は 409")
        void doubleSubmission() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("誰が実行できるか")
    class Authorization {

        /**
         * <strong>自分の勤怠は自分で承認できない</strong>（BR-11 の 4）。
         * 課長本人の勤怠は上位へ遡るが、ここでは山田の勤怠を山田が承認しようとする。
         */
        @Test
        @DisplayName("IT-APV-36 本人は自分の勤怠を承認できない")
        void selfApproval() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            transition("approval", yamada, "E0001", null, Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("IT-APV-37 承認者でない社員は承認できない")
        void notTheApprover() throws Exception {
            var other = hire("E0002", "無関係 三郎", Optional.empty(), Role.EMPLOYEE);
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            transition("approval", other, "E0002", null, Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());
        }

        /**
         * <strong>人事はいつでも承認できるわけではない。</strong>
         * BR-11 の 5 は「遡っても承認者が得られない場合」に限って人事へ回す。
         * 人事に無条件の承認権を与えると、BR-11 の 1〜4 を迂回する経路ができる。
         *
         * <p>人事へエスカレートした場合に承認できることは UT-BR11-09 が確かめている。
         */
        @Test
        @DisplayName("IT-APV-38 個人の承認者がいる月は、人事でも承認できない")
        void humanResourcesCannotBypassTheApprover() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            transition("approval", hr, "E0900", null, Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("IT-APV-39 人事でなければ締められない")
        void onlyHumanResourcesCanClose() throws Exception {
            submitAndApprove();

            transition("closure", manager, "E0100", null, Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());
        }

        /**
         * <strong>在籍している社員の勤怠を人事が代理提出することは認めない。</strong>
         * 本人が提出できる状態なら、本人が提出する。
         */
        @Test
        @DisplayName("IT-APV-40 在籍中の社員の勤怠は人事でも代理提出できない")
        void noProxySubmissionForActiveEmployee() throws Exception {
            transition("submission", hr, "E0900", "{\"comment\":\"代理で提出\"}",
                    Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("理由の必須")
    class ReasonRequired {

        @Test
        @DisplayName("IT-APV-41 差戻しは理由が必須")
        void rejectionRequiresReason() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            transition("rejection", manager, "E0100", "{\"reason\":\"  \"}",
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isBadRequest());

            transition("rejection", manager, "E0100", "{\"reason\":\"打刻漏れがあります\"}",
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        /**
         * <strong>差戻しと訂正による巻き戻しを証跡で区別する。</strong>
         * どちらも提出済 → 下書きだが、差戻しは承認者の判断で、
         * 訂正は内容が変わったことによるもので本人に非が無い。
         */
        @Test
        @DisplayName("IT-APV-42 差戻しは REJECT として証跡に残る")
        void rejectionIsRecordedAsReject() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());
            transition("rejection", manager, "E0100", "{\"reason\":\"打刻漏れがあります\"}",
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk());

            assertThat(jdbc.queryForList("""
                    SELECT event_kind FROM approval_events ORDER BY occurred_at, created_at
                    """, String.class))
                    .containsExactly("SUBMIT", "REJECT");
            assertThat(jdbc.queryForObject("""
                    SELECT comment FROM approval_events WHERE event_kind = 'REJECT'
                    """, String.class)).isEqualTo("打刻漏れがあります");
        }

        @Test
        @DisplayName("IT-APV-43 承認の取消も理由が必須")
        void revocationRequiresReason() throws Exception {
            submitAndApprove();

            transition("approval-revocation", hr, "E0900", "{\"reason\":\"\"}",
                    Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isBadRequest());

            transition("approval-revocation", hr, "E0900",
                    "{\"reason\":\"集計に誤りがありました\"}", Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }
    }

    @Nested
    @DisplayName("提出の事前条件")
    class SubmitPreconditions {

        /**
         * <strong>対象月の末日が到来していることを確かめる。</strong>
         * これを見ないと、月初でも「未確定の日が無い」ので提出・承認・締めが通る。
         * 締めてしまうと戻す手段が無い。
         */
        @Test
        @DisplayName("IT-APV-44 まだ終わっていない月は提出できない")
        void monthNotFinished() throws Exception {
            mockMvc.perform(post(
                            "/api/employees/{id}/monthly-attendances/{month}/submission",
                            yamada.value(), "2026-05")
                            .with(as(yamada, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-not-finished"));
        }

        /** 未確定の日があると提出できない。<strong>どの日かを返す。</strong> */
        @Test
        @DisplayName("IT-APV-45 未計算の勤務日があると提出できず、その日付が返る")
        void incompleteDays() throws Exception {
            dailyAttendances.save(yamada, new DailyAttendanceCalculator(calendar)
                    .calculate(LocalDate.of(2026, 4, 1),
                            timeClocks.findByWorkDate(yamada, LocalDate.of(2026, 4, 1)),
                            workRules.findEffective(yamada, LocalDate.of(2026, 4, 1))
                                    .orElseThrow()),
                    workRules.findEffective(yamada, LocalDate.of(2026, 4, 1))
                            .orElseThrow().id());
            jdbc.update("DELETE FROM daily_attendances WHERE work_date = '2026-04-02'");

            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:daily-attendance-incomplete"))
                    .andExpect(jsonPath("$.incompleteDates[0]").value("2026-04-02"));
        }
    }

    @Nested
    @DisplayName("承認者の照会")
    class ApproverLookup {

        /** <strong>遡った経路も返す。</strong>「なぜこの人か」は必ず問い合わせが来る。 */
        @Test
        @DisplayName("IT-APV-46 承認者と、そこへ至った経路を返す")
        void approver() throws Exception {
            mockMvc.perform(get(
                            "/api/employees/{id}/monthly-attendances/{month}/approver",
                            yamada.value(), "2026-04")
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("INDIVIDUAL"))
                    .andExpect(jsonPath("$.employeeId").value(manager.value().toString()))
                    .andExpect(jsonPath("$.path[0].departmentName").value("営業部"))
                    .andExpect(jsonPath("$.path[0].reason").value("NONE"));
        }

        /** 承認待ち一覧は<strong>見てよい社員のぶんだけ</strong>返る。 */
        @Test
        @DisplayName("IT-APV-47 承認待ち一覧は配下の社員だけを返す")
        void pendingApproval() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/monthly-attendances/pending-approval")
                            .param("month", "2026-04")
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].employeeId")
                            .value(yamada.value().toString()));

            var outsider = hire("E0003", "他部署 四郎", Optional.empty(), Role.EMPLOYEE);
            mockMvc.perform(get("/api/monthly-attendances/pending-approval")
                            .param("month", "2026-04")
                            .with(as(outsider, "E0003", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    /**
     * 楽観ロック（API設計書 1.1）。
     *
     * <p>承認者が一覧を開いてから承認するまでの間に、
     * 訂正の承認で内容が変わることがある。
     * 突き合わせないと<strong>承認者が見ていない内容が承認済みになる。</strong>
     */
    @Nested
    @DisplayName("楽観ロック")
    class OptimisticLock {

        @Test
        @DisplayName("IT-APV-48 GET は版を返す")
        void getReturnsVersion() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/monthly-attendances/{month}",
                            yamada.value(), "2026-04")
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.version").value(0));

            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1));

            mockMvc.perform(get("/api/employees/{id}/monthly-attendances/{month}",
                            yamada.value(), "2026-04")
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(jsonPath("$.version").value(1));
        }

        /**
         * <strong>古い版での承認は拒否される。</strong>
         * 版を送らない実装だと、ここが黙って通る。
         */
        @Test
        @DisplayName("IT-APV-49 古い版で承認すると 409 になる")
        void staleVersionIsRejected() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            // 提出で版は 1 になっている。承認者は 0 のまま画面を見ていた
            transition("approval", manager, "E0100", null, 0L,
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));

            assertThat(statusOf()).isEqualTo("SUBMITTED");
        }

        /** 版を送らない要求は受け付けない。<strong>既定値の 0 で通してはならない。</strong> */
        @Test
        @DisplayName("IT-APV-50 版の無い要求は 400 になる")
        void versionIsRequired() throws Exception {
            mockMvc.perform(post(
                            "/api/employees/{id}/monthly-attendances/{month}/submission",
                            yamada.value(), "2026-04")
                            .with(as(yamada, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * 一括締め（API設計書 2.7）。
     *
     * <p><strong>1 人でも締められない社員がいても、全体を失敗させない。</strong>
     */
    @Nested
    @DisplayName("一括締め")
    class BulkClosure {

        private ResultActions closeAll(EmployeeId actor, String number, String body,
                                       Role... roles) throws Exception {
            return mockMvc.perform(post("/api/monthly-attendances/bulk-closure")
                    .with(as(actor, number, roles))
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }

        /**
         * <strong>締められた社員と、締められなかった社員が両方返る。</strong>
         * 未承認の 1 人を例外にすると、承認済みの社員の締めまで巻き戻る。
         */
        @Test
        @DisplayName("IT-APV-51 未承認の社員が混ざっても、承認済みの社員は締まる")
        void partialSuccess() throws Exception {
            submitAndApprove();

            closeAll(hr, "E0900", """
                    {"month":"2026-04","employeeIds":["%s","%s"]}
                    """.formatted(yamada.value(), manager.value()),
                    Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closed").value(1))
                    .andExpect(jsonPath("$.skipped.length()").value(1))
                    .andExpect(jsonPath("$.skipped[0].employeeId")
                            .value(manager.value().toString()))
                    .andExpect(jsonPath("$.skipped[0].status").value("DRAFT"))
                    .andExpect(jsonPath("$.skipped[0].reason").value("提出されていません"));

            assertThat(statusOf()).isEqualTo("CLOSED");
        }

        /** 提出済（未承認）は<strong>「承認されていません」</strong>として返る。 */
        @Test
        @DisplayName("IT-APV-52 提出済のままの社員は理由つきで返る")
        void submittedIsSkippedWithReason() throws Exception {
            transition("submission", yamada, "E0001", null, Role.EMPLOYEE)
                    .andExpect(status().isOk());

            closeAll(hr, "E0900",
                    "{\"month\":\"2026-04\",\"employeeIds\":[\"%s\"]}"
                            .formatted(yamada.value()),
                    Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closed").value(0))
                    .andExpect(jsonPath("$.skipped[0].status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.skipped[0].reason").value("承認されていません"));

            assertThat(statusOf()).isEqualTo("SUBMITTED");
        }

        /**
         * <strong>依頼そのものの不備は例外のまま返す。</strong>
         * 人事でない利用者に全員ぶんの {@code skipped} を返すと、
         * 「自分に権限が無い」ことに気づけない。
         */
        @Test
        @DisplayName("IT-APV-53 人事でなければ一括締めできない")
        void requiresHumanResources() throws Exception {
            submitAndApprove();

            closeAll(manager, "E0100",
                    "{\"month\":\"2026-04\",\"employeeIds\":[\"%s\"]}"
                            .formatted(yamada.value()),
                    Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());

            assertThat(statusOf()).isEqualTo("APPROVED");
        }

        /** {@code employeeIds} を省くと全社員が対象になる。 */
        @Test
        @DisplayName("IT-APV-54 対象を省くと全社員が対象になる")
        void allEmployees() throws Exception {
            submitAndApprove();

            closeAll(hr, "E0900", "{\"month\":\"2026-04\"}", Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closed").value(1));

            assertThat(statusOf()).isEqualTo("CLOSED");
        }

        /**
         * <strong>退職者も対象に含める。</strong>
         * 4/15 退職の社員も 4/1〜4/15 は働いており、4 月分は締めなければならない。
         * <strong>月末に在籍している社員だけを対象にすると、その月が永久に締まらない。</strong>
         */
        @Test
        @DisplayName("IT-APV-55 全社員が対象なら、月中に退職した社員も含まれる")
        void includesRetiredEmployees() throws Exception {
            var retired = hire("E0004", "退職 五郎",
                    Optional.of(LocalDate.of(2026, 4, 15)), Role.EMPLOYEE);
            submitAndApprove();

            closeAll(hr, "E0900", "{\"month\":\"2026-04\"}", Role.EMPLOYEE, Role.HR)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closed").value(1))
                    .andExpect(jsonPath("$.skipped[?(@.employeeId=='%s')]"
                            .formatted(retired.value())).exists());
        }
    }
}
