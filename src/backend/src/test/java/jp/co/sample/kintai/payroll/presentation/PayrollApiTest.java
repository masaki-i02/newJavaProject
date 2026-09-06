package jp.co.sample.kintai.payroll.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.approval.application.MonthlyAttendanceService;
import jp.co.sample.kintai.attendance.application.TimeClockService;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 給与連携の API（IT-PAY-28〜51・BR-18）。
 *
 * <p>出力は<strong>締め済みの月次清算を読むだけ</strong>なので、
 * 前提として打刻 → 提出 → 承認 → 締め までを本番の経路で作る。
 * 手で行を入れると、締めの状態と清算の値が噛み合っているかを検査しない。
 */
@DisplayName("給与連携の API（BR-18）")
class PayrollApiTest extends WebIntegrationTestBase {

    /** 5 月分を出力する。対象月が終わっている必要がある（BR-10）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);
    private static final YearMonth MAY = YearMonth.of(2026, 5);
    private static final LocalDate HIRED = LocalDate.of(2020, 4, 1);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(10, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private WorkRuleSeriesRepository series;
    @Autowired
    private WorkRuleRepository workRules;
    @Autowired
    private TimeClockService timeClocks;
    @Autowired
    private MonthlyAttendanceService attendances;

    @Autowired
    private jp.co.sample.kintai.employee.domain.DepartmentRepository departments;
    @Autowired
    private jp.co.sample.kintai.employee.domain.AssignmentRepository assignments;
    @Autowired
    private jp.co.sample.kintai.employee.domain.ManagershipRepository managerships;

    private EmployeeId taro;
    private EmployeeId hr;
    private EmployeeId boss;
    private WorkRuleSeriesId standard;
    private jp.co.sample.kintai.employee.domain.DepartmentId sales;

    @BeforeEach
    void setUpMasterData() {
        taro = hire("E0001", HIRED, Optional.empty(), Role.EMPLOYEE);
        hr = hire("E0900", HIRED, Optional.empty(), Role.EMPLOYEE, Role.HR);
        standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        boss = hire("E0500", HIRED, Optional.empty(), Role.EMPLOYEE);
        series.assign(taro, standard, HIRED);
        series.assign(hr, standard, HIRED);
        series.assign(boss, standard, HIRED);

        // ★ 承認者は組織から導かれる（BR-11）。部署と部署長を用意しないと承認できない
        sales = new jp.co.sample.kintai.employee.domain.DepartmentId(UUID.randomUUID());
        departments.save(jp.co.sample.kintai.employee.domain.Department.root(sales,
                new jp.co.sample.kintai.employee.domain.DepartmentCode("SALES"), "営業部"));
        assign(taro);
        assign(hr);
        assign(boss);
        managerships.save(jp.co.sample.kintai.employee.domain.Managership
                .startingAt(sales, boss, HIRED));

        // ★ 年度の全日を登録する。1 日でも欠けると分母が確かめられず 422 になる
        registerFiscalYear(2026);
    }

    private void assign(EmployeeId employeeId) {
        assignments.save(jp.co.sample.kintai.employee.domain.Assignment
                .startingAt(employeeId, sales, HIRED));
    }

    @Nested
    @DisplayName("出力を作る")
    class Export {

        @Test
        @DisplayName("IT-PAY-28 締め済みの社員だけが出力され、未締めは理由つきで除外される")
        void closedEmployeesAreExported() throws Exception {
            workAndClose(taro, MAY);

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.rowCount").value(1))
                    .andExpect(jsonPath("$.excluded.length()")
                            .value(2))   // 打刻していない人事と部署長
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0900')].reason")
                            .value("NO_ATTENDANCE_RECORD"))
                    .andExpect(jsonPath("$.monthlyAverageMinutes").value(10_440));
        }

        /**
         * <strong>「月次勤怠の行が無い」を「打刻が 1 件も無い」と読まない。</strong>
         * 行は提出のときに初めて作られるので、1 か月まるまる働いて提出していない社員も
         * 行を持たない。打刻という一次証拠で分ける（落とし穴 120）。
         */
        @Test
        @DisplayName("IT-PAY-29 1 か月働いて提出していない社員は未提出であって打刻無しではない")
        void workedButNotSubmitted() throws Exception {
            workAllMonth(taro, MAY);

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0001')].reason")
                            .value("NOT_SUBMITTED"));
        }

        @Test
        @DisplayName("IT-PAY-31 未退勤の勤務日が残っている社員は提出できないので分けて返す")
        void notSubmittable() throws Exception {
            punch(taro, LocalDate.of(2026, 5, 7), TimeClockEvent.Type.CLOCK_IN, 9);

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0001')].reason")
                            .value("NOT_SUBMITTABLE"));
        }

        @Test
        @DisplayName("IT-PAY-34 全員が未締めでも 201。例外にしない")
        void nobodyClosed() throws Exception {
            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.rowCount").value(0))
                    .andExpect(jsonPath("$.excluded.length()").value(3));
        }

        @Test
        @DisplayName("IT-PAY-35 対象月がまだ終わっていないと 409")
        void monthNotFinished() throws Exception {
            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-06\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-not-finished"));
        }

        @Test
        @DisplayName("IT-PAY-36 人事でない利用者は 403")
        void notHumanResources() throws Exception {
            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }

        /** 認証がまだなら 401。302 でログイン画面へ飛ばさない（要件 4）。 */
        @Test
        @DisplayName("IT-PAY-37 未認証は 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(get("/api/payroll/exports"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * <strong>月中退職の社員を除かない。</strong>
         * 除くと最終月の給与が出ない。月末退職で書くと、
         * 「月末時点の在籍で絞る」実装が生き残る（落とし穴 63）。
         */
        @Test
        @DisplayName("IT-PAY-40 月中退職の社員も対象になる")
        void retiredMidMonth() throws Exception {
            EmployeeId jiro = hire("E0002", HIRED,
                    Optional.of(LocalDate.of(2026, 5, 15)), Role.EMPLOYEE);
            series.assign(jiro, standard, HIRED);

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0002')]")
                            .isNotEmpty());
        }

        @Test
        @DisplayName("IT-PAY-41 対象月より後に入社した社員は対象にならない")
        void hiredAfterTheMonth() throws Exception {
            hire("E0003", LocalDate.of(2026, 6, 1), Optional.empty(), Role.EMPLOYEE);

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0003')]")
                            .isEmpty());
        }

        /**
         * <strong>社員番号は在籍者のあいだでしか一意でない。</strong>
         * 月中退職と同月の入社が重なると、同じ CSV に同じ番号の行が 2 つ並び、
         * 給与側が別人を同一人物として名寄せする（落とし穴 121）。
         */
        @Test
        @DisplayName("IT-PAY-33 同じ社員番号が対象月に 2 人いると、その 2 人を除外する")
        void duplicateEmployeeNumber() throws Exception {
            workAndClose(taro, MAY);
            EmployeeId retired = hire("E0777", HIRED,
                    Optional.of(LocalDate.of(2026, 5, 15)), Role.EMPLOYEE);
            EmployeeId rehired = hire("E0777", LocalDate.of(2026, 5, 16),
                    Optional.empty(), Role.EMPLOYEE);
            series.assign(retired, standard, HIRED);
            series.assign(rehired, standard, LocalDate.of(2026, 5, 16));

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath(
                            "$.excluded[?(@.reason=='DUPLICATE_EMPLOYEE_NUMBER')]")
                            .value(org.hamcrest.Matchers.hasSize(2)))
                    .andExpect(jsonPath("$.rowCount").value(1));
        }
    }

    @Nested
    @DisplayName("CSV を取得する")
    class Csv {

        @Test
        @DisplayName("IT-PAY-42 ヘッダ行と社員番号順の行が返る")
        void csvHasHeaderAndRows() throws Exception {
            workAndClose(taro, MAY);
            String id = createExport();

            String csv = mockMvc.perform(get("/api/payroll/exports/" + id)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(csv).startsWith("﻿社員番号,対象月,清算期間開始,清算期間終了");
            assertThat(csv.lines().count()).as("ヘッダ行 + 1 名").isEqualTo(2);
            assertThat(csv).contains("E0001,2026-05,2026-05-01,2026-05-31,FIXED");
        }

        /**
         * <strong>行の値が動かないことと、行の集合が動かないことは別である。</strong>
         * 記録のあとに人事が残りの社員を締めても、同じ id の CSV は増えない。
         * 締めを挟まないケースだけを書くと、集合を固定していない実装でも通る
         * （落とし穴 24・122）。
         */
        @Test
        @DisplayName("IT-PAY-38 取得の間に別の社員を締めても行は増えない")
        void csvIsClosedOverTheRecordedTargets() throws Exception {
            workAndClose(taro, MAY);
            String id = createExport();

            workAndClose(hr, MAY);

            String csv = mockMvc.perform(get("/api/payroll/exports/" + id)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(csv.lines().count()).as("記録した対象社員に閉じる").isEqualTo(2);
        }

        @Test
        @DisplayName("IT-PAY-43 存在しない id は 404")
        void unknownExport() throws Exception {
            mockMvc.perform(get("/api/payroll/exports/" + UUID.randomUUID())
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:payroll-export-not-found"));
        }

        /**
         * <strong>所定内 + 所定超 = 実労働。</strong>
         * 給与側はこの 2 つで基礎賃金を払う。
         */
        @Test
        @DisplayName("IT-PAY-47 CSV の各行で所定内 + 所定超が実労働に一致する")
        void csvRowsAreConsistent() throws Exception {
            workAndClose(taro, MAY);
            String id = createExport();

            String csv = mockMvc.perform(get("/api/payroll/exports/" + id)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            String[] columns = csv.lines().skip(1).findFirst().orElseThrow().split(",");
            int working = Integer.parseInt(columns[9]);
            int inside = Integer.parseInt(columns[10]);
            int beyond = Integer.parseInt(columns[11]);
            assertThat(inside + beyond).isEqualTo(working);
            assertThat(working).as("21 日 × 8 時間").isEqualTo(21 * 480);
        }
    }

    @Nested
    @DisplayName("年間の所定")
    class Annual {

        @Test
        @DisplayName("IT-PAY-49 年度の所定労働日数と 1 か月平均が返る")
        void annualScheduledHours() throws Exception {
            mockMvc.perform(get("/api/payroll/scheduled-hours/2026")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fiscalYear").value(2026))
                    .andExpect(jsonPath("$.period.from").value("2026-04-01"))
                    .andExpect(jsonPath("$.period.toExclusive").value("2027-04-01"))
                    .andExpect(jsonPath("$.scheduledDays").value(261))
                    .andExpect(jsonPath("$.monthlyAverageMinutes").value(10_440));
        }

        /**
         * <strong>年度の一部しか登録していないと拒否する。</strong>
         * 法定休日の有無で判定すると、4 月だけ登録した年度が通ってしまう。
         */
        @Test
        @DisplayName("IT-PAY-50 カレンダーが揃っていない年度は 422")
        void calendarNotRegistered() throws Exception {
            mockMvc.perform(get("/api/payroll/scheduled-hours/2027")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:calendar-not-registered"));
        }

        @Test
        @DisplayName("IT-PAY-51 人事でない利用者は 403（カレンダーの整備状況を漏らさない）")
        void onlyHumanResources() throws Exception {
            mockMvc.perform(get("/api/payroll/scheduled-hours/2027")
                            .with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }
    }

    @Nested
    @DisplayName("出力の記録")
    class Records {

        @Test
        @DisplayName("IT-PAY-44 同じ月を 2 回出力でき、記録が 2 件になる")
        void sameMonthTwice() throws Exception {
            workAndClose(taro, MAY);
            createExport();
            createExport();

            mockMvc.perform(get("/api/payroll/exports?month=2026-05")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.exports.length()").value(2));
        }

        /**
         * <strong>月平均という導出値だけでは検算できない。</strong>
         * 支払の根拠として、そのとき使った値を記録に残す。
         */
        @Test
        @DisplayName("IT-PAY-45 記録に、その出力で使った 1 か月平均が残る")
        void divisorIsRecorded() throws Exception {
            workAndClose(taro, MAY);
            createExport();

            mockMvc.perform(get("/api/payroll/exports")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.exports[0].monthlyAverageMinutes").value(10_440))
                    .andExpect(jsonPath("$.exports[0].rowCount").value(1))
                    .andExpect(jsonPath("$.exports[0].excludedCount").value(2));
        }
    }

    private String createExport() throws Exception {
        String body = mockMvc.perform(post("/api/payroll/exports")
                        .contentType("application/json")
                        .content("{\"month\":\"2026-05\"}")
                        .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("exportId").asString();
    }

    private EmployeeId hire(String number, LocalDate hiredOn,
                            Optional<LocalDate> retiredOn, Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), number + " の人",
                new Email(UUID.randomUUID() + "@example.com"), hiredOn, retiredOn,
                Set.of(roles)));
        return id;
    }

    /** 本人として打刻する。<strong>代理の打刻は認めていない。</strong> */
    private void punch(EmployeeId employeeId, LocalDate date,
                       TimeClockEvent.Type type, int hour) {
        timeClocks.punch(new Requester(employeeId, Set.of(Role.EMPLOYEE)), employeeId,
                type, Optional.of(date.atTime(hour, 0)));
    }

    /** その月の所定労働日すべてに 9:00–18:00（休憩 1 時間）の打刻を入れる。 */
    private void workAllMonth(EmployeeId employeeId, YearMonth month) {
        for (LocalDate date = month.atDay(1); date.isBefore(month.plusMonths(1).atDay(1));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() >= 6) {
                continue;
            }
            punch(employeeId, date, TimeClockEvent.Type.CLOCK_IN, 9);
            punch(employeeId, date, TimeClockEvent.Type.BREAK_START, 12);
            punch(employeeId, date, TimeClockEvent.Type.BREAK_END, 13);
            punch(employeeId, date, TimeClockEvent.Type.CLOCK_OUT, 18);
        }
    }

    /** 打刻 → 提出 → 承認 → 締め まで本番の経路で通す。 */
    private void workAndClose(EmployeeId employeeId, YearMonth month) {
        workAllMonth(employeeId, month);
        Requester self = new Requester(employeeId, Set.of(Role.EMPLOYEE));
        Requester humanResources = new Requester(hr, Set.of(Role.EMPLOYEE, Role.HR));
        // ★ 承認は部署長が行う（BR-11）。人事が承認できるのは承認者を導けない場合だけ
        Requester approver = new Requester(boss, Set.of(Role.EMPLOYEE, Role.APPROVER));
        attendances.submit(self, employeeId, month, Optional.empty(), 0L);
        Requester decider = employeeId.equals(boss) ? humanResources : approver;
        attendances.approve(decider, employeeId, month,
                attendances.currentVersion(humanResources, employeeId, month));
        attendances.close(humanResources, employeeId, month,
                attendances.currentVersion(humanResources, employeeId, month));
    }

    /** 年度の全日を登録する。土=所定休日・日=法定休日・他=所定労働日。 */
    private void registerFiscalYear(int fiscalYear) {
        jdbc.update("""
                INSERT INTO company_calendars (calendar_date, day_type, name)
                SELECT d::date,
                       CASE EXTRACT(DOW FROM d)
                            WHEN 0 THEN 'LEGAL_HOLIDAY'
                            WHEN 6 THEN 'NON_LEGAL_HOLIDAY'
                            ELSE 'WORKDAY' END,
                       NULL
                  FROM generate_series(?::date, ?::date, '1 day') d
                ON CONFLICT (calendar_date) DO NOTHING
                """,
                LocalDate.of(fiscalYear, 4, 1), LocalDate.of(fiscalYear + 1, 3, 31));
    }
}
