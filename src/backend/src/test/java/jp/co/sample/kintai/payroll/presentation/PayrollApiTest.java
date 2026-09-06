package jp.co.sample.kintai.payroll.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 給与連携の API（IT-PAY-27〜51・BR-18）。
 *
 * <p>出力は<strong>締め済みの月次清算を読むだけ</strong>なので、
 * 前提として打刻 → 提出 → 承認 → 締め までを本番の経路で作る。
 * 手で行を入れると、締めの状態と清算の値が噛み合っているかを検査しない。
 */
@DisplayName("給与連携の API（BR-18）")
class PayrollApiTest extends WebIntegrationTestBase {

    /**
     * CSV の列位置。<strong>数字を各所に散らさない。</strong>
     * 列を 1 つ足したときに直す場所が 1 か所で済む。
     */
    private static final int 社員番号 = 0;
    private static final int 暦月所定労働日数 = 5;
    private static final int 清算期間所定労働日数 = 6;
    private static final int 出勤日数 = 7;
    private static final int 年休日数 = 8;
    private static final int 欠勤日数 = 9;
    private static final int 実労働 = 10;
    private static final int 所定内 = 11;
    private static final int 所定超 = 12;
    private static final int 残業60hまで = 13;
    private static final int 残業60h超 = 14;
    private static final int 法定休日 = 15;
    private static final int 深夜 = 16;
    private static final int 所定総 = 17;
    private static final int 不足 = 18;

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

        @Test
        @DisplayName("IT-PAY-27 締め済みの月を出力すると在籍者ぶんの結果が返る")
        void closedMonthIsExported() throws Exception {
            workAndClose(taro, MAY);
            workAndClose(boss, MAY);

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.rowCount").value(2))
                    .andExpect(jsonPath("$.excluded.length()").value(1));
        }

        @Test
        @DisplayName("IT-PAY-30 その月の打刻が 1 件も無い社員は休業か打刻漏れとして返る")
        void noAttendanceRecord() throws Exception {
            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0001')].reason")
                            .value("NO_ATTENDANCE_RECORD"));
        }

        /** 状態ごとに違う案内を返す。まとめると人事が次に何をすべきか分からない。 */
        @Test
        @DisplayName("IT-PAY-32 提出済みは未承認、承認済みは未締めとして分けて返る")
        void submittedAndApprovedAreDistinguished() throws Exception {
            workAllMonth(taro, MAY);
            attendances.submit(new Requester(taro, Set.of(Role.EMPLOYEE)), taro, MAY,
                    Optional.empty(), 0L);

            workAllMonth(boss, MAY);
            Requester humanResources = new Requester(hr, Set.of(Role.EMPLOYEE, Role.HR));
            attendances.submit(new Requester(boss, Set.of(Role.EMPLOYEE)), boss, MAY,
                    Optional.empty(), 0L);
            attendances.approve(humanResources, boss, MAY,
                    attendances.currentVersion(humanResources, boss, MAY));

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0001')].reason")
                            .value("NOT_APPROVED"))
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0500')].reason")
                            .value("NOT_CLOSED"));
        }

        /**
         * <strong>月中入社の社員の清算期間は在籍期間との交差になる。</strong>
         * 暦月で数えると、入社前の日まで所定労働日に数えて不足時間が水増しされる。
         */
        @Test
        @DisplayName("IT-PAY-39 月中入社の社員も対象になる")
        void hiredMidMonth() throws Exception {
            EmployeeId newcomer = hire("E0004", LocalDate.of(2026, 5, 18),
                    Optional.empty(), Role.EMPLOYEE);
            series.assign(newcomer, standard, LocalDate.of(2026, 5, 18));

            mockMvc.perform(post("/api/payroll/exports")
                            .contentType("application/json")
                            .content("{\"month\":\"2026-05\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0004')]")
                            .isNotEmpty());
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

        /**
         * <strong>CSV の値は保存済みの月次清算そのものである。</strong>
         * 出力のたびに計算し直すと、会社カレンダーの過去分が変わったときに値が動く。
         */
        @Test
        @DisplayName("IT-PAY-46 CSV の値が保存済みの月次清算と一致する")
        void csvMatchesTheStoredSettlement() throws Exception {
            workAndClose(taro, MAY);
            String id = createExport();

            String csv = mockMvc.perform(get("/api/payroll/exports/" + id)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String[] columns = csv.lines().skip(1).findFirst().orElseThrow().split(",");

            Integer stored = jdbc.queryForObject("""
                    SELECT working_minutes FROM monthly_settlements
                     WHERE employee_id = ? AND target_month = DATE '2026-05-01'
                    """, Integer.class, taro.value());
            assertThat(Integer.parseInt(columns[実労働])).isEqualTo(stored);
        }

        /**
         * <strong>法定休日には所定が無いので、法定休日労働は必ず所定超に入る。</strong>
         * 1.0 + 0.35 が支払われる。
         */
        @Test
        @DisplayName("IT-PAY-48 法定休日に働いた月は所定超と法定休日の両方に入る")
        void legalHolidayWorkAppearsInBothColumns() throws Exception {
            workAllMonth(taro, MAY);
            // 5/10 は日曜（法定休日）
            LocalDate sunday = LocalDate.of(2026, 5, 10);
            punch(taro, sunday, TimeClockEvent.Type.CLOCK_IN, 9);
            punch(taro, sunday, TimeClockEvent.Type.CLOCK_OUT, 17);
            Requester self = new Requester(taro, Set.of(Role.EMPLOYEE));
            Requester humanResources = new Requester(hr, Set.of(Role.EMPLOYEE, Role.HR));
            Requester approver = new Requester(boss, Set.of(Role.EMPLOYEE, Role.APPROVER));
            attendances.submit(self, taro, MAY, Optional.empty(), 0L);
            attendances.approve(approver, taro, MAY,
                    attendances.currentVersion(humanResources, taro, MAY));
            attendances.close(humanResources, taro, MAY,
                    attendances.currentVersion(humanResources, taro, MAY));
            String id = createExport();

            String csv = mockMvc.perform(get("/api/payroll/exports/" + id)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String[] columns = csv.lines().skip(1).findFirst().orElseThrow().split(",");

            assertThat(Integer.parseInt(columns[法定休日]))
                    .as("法定休日労働 8 時間").isEqualTo(480);
            assertThat(Integer.parseInt(columns[所定超]))
                    .as("法定休日の所定は 0 なので、所定超にも 8 時間が入る")
                    .isGreaterThanOrEqualTo(480);
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
         * <strong>「所定内 + 所定超 = 実労働」を期待に書かない。</strong>
         * {@code beyondScheduledTime()} が {@code workingTime − scheduledInsideTime()}
         * として定義されているので、その等式は<strong>定義を代入しただけ</strong>であり、
         * 所定内の式を何に変えても成り立つ（CLAUDE.md 落とし穴 117）。
         * 実数で書く。
         */
        @Test
        @DisplayName("IT-PAY-47 定時で働いた月は全部が所定内に入り、所定超は 0 になる")
        void csvSplitsBaseWageByActualNumbers() throws Exception {
            workAndClose(taro, MAY);
            String id = createExport();

            String[] columns = csvOf(id);
            assertThat(Integer.parseInt(columns[実労働]))
                    .as("所定労働日 21 日 × 8 時間").isEqualTo(21 * 480);
            assertThat(Integer.parseInt(columns[所定内]))
                    .as("所定総 − 不足 = 21 日 × 8 時間").isEqualTo(21 * 480);
            assertThat(Integer.parseInt(columns[所定超]))
                    .as("所定を超えて働いていない").isZero();
            assertThat(Integer.parseInt(columns[所定総])).isEqualTo(21 * 480);
            assertThat(Integer.parseInt(columns[不足])).isZero();
        }

        /**
         * <strong>労基則 54 条 4 号の労働日数と、日額の分母を確かめる。</strong>
         * どのテストからも読まれていない列は、取り違えても誰も気づけない
         * （落とし穴 112）。
         */
        @Test
        @DisplayName("IT-PAY-53 日数の列が数えたとおりに並ぶ")
        void csvCarriesDayCounts() throws Exception {
            workAllMonth(taro, MAY);
            // 5/8（金）は所定労働日。打刻しないので欠勤 1 日になる
            closeAfterWork(taro, MAY);
            String id = createExport();

            String[] columns = csvOf(id);
            assertThat(Integer.parseInt(columns[暦月所定労働日数]))
                    .as("2026 年 5 月の平日").isEqualTo(21);
            assertThat(Integer.parseInt(columns[清算期間所定労働日数]))
                    .as("在籍期間で切っていない月は暦月と同じ").isEqualTo(21);
            assertThat(Integer.parseInt(columns[出勤日数])).isEqualTo(21);
            assertThat(Integer.parseInt(columns[年休日数])).isZero();
            assertThat(Integer.parseInt(columns[欠勤日数])).isZero();
        }

        /**
         * <strong>60 時間の分かれ目は 25% と 50% の境界である。</strong>
         * 2 つの列を読まないと、渡す順序を入れ替えても検出できない。
         */
        @Test
        @DisplayName("IT-PAY-54 月 60 時間超の残業は 2 つの列に分かれて出る")
        void csvSplitsOvertimeAt60Hours() throws Exception {
            workAllMonth(taro, MAY, 12);   // 1 日 12 時間 × 21 日 = 4 時間 × 21 = 84 時間の残業
            closeAfterWork(taro, MAY);
            String id = createExport();

            String[] columns = csvOf(id);
            int upTo60 = Integer.parseInt(columns[残業60hまで]);
            int over60 = Integer.parseInt(columns[残業60h超]);
            assertThat(upTo60).as("60 時間まで").isEqualTo(60 * 60);
            assertThat(over60).as("60 時間を超えたぶん").isPositive();
            assertThat(upTo60 + over60).as("合計が時間外労働").isEqualTo(84 * 60);
        }

        /**
         * <strong>深夜は排他区分と別の列で渡す</strong>（労基則 20 条の上乗せ）。
         * 深夜の列を 1 度も読まないと、0 を返す実装が生き残る。
         */
        @Test
        @DisplayName("IT-PAY-55 深夜に働いた月は深夜の列が立つ")
        void csvCarriesNightTime() throws Exception {
            workAllMonth(taro, MAY);
            // 5/16（土・所定休日）に 20:00〜23:00 働く。深夜帯は 22:00〜23:00 の 1 時間
            LocalDate saturday = LocalDate.of(2026, 5, 16);
            punch(taro, saturday, TimeClockEvent.Type.CLOCK_IN, 20);
            punch(taro, saturday, TimeClockEvent.Type.CLOCK_OUT, 23);
            closeAfterWork(taro, MAY);
            String id = createExport();

            String[] columns = csvOf(id);
            assertThat(Integer.parseInt(columns[深夜])).as("22:00〜23:00").isEqualTo(60);
            assertThat(Integer.parseInt(columns[深夜]))
                    .as("深夜は実労働の内側にある")
                    .isLessThanOrEqualTo(Integer.parseInt(columns[実労働]));
        }

        /**
         * <strong>CSV は RFC 4180 に従い CRLF で区切る。</strong>
         * {@code String.lines()} は LF でも CRLF でも同じ数を返すので、
         * 行数だけを見ていると LF へ変えても落ちない（落とし穴 112）。
         */
        @Test
        @DisplayName("IT-PAY-56 行の区切りは CRLF で、見出しは 19 列ある")
        void csvUsesCrlfAndFullHeader() throws Exception {
            workAndClose(taro, MAY);
            String id = createExport();

            String csv = rawCsvOf(id);
            assertThat(csv).contains("\r\n");
            String header = csv.substring(1, csv.indexOf("\r\n"));   // 先頭の BOM を除く
            assertThat(header.split(",")).hasSize(19);
            assertThat(header).endsWith("所定総,不足");
            assertThat(header.split(",")[暦月所定労働日数]).isEqualTo("暦月所定労働日数");
        }

        /**
         * <strong>複数行の CSV を 1 度は作る。</strong>
         * 1 行しか出さないテストばかりだと、並び順を決める実装を消しても落ちない。
         */
        @Test
        @DisplayName("IT-PAY-57 行は社員番号の昇順に並ぶ")
        void csvRowsAreSortedByEmployeeNumber() throws Exception {
            workAndClose(boss, MAY);   // E0500
            workAndClose(taro, MAY);   // E0001
            String id = createExport();

            String csv = rawCsvOf(id);
            assertThat(csv.lines().skip(1).map(line -> line.split(",")[社員番号]).toList())
                    .containsExactly("E0001", "E0500");
        }

        /**
         * <strong>月中入社の月は、日額の分母（暦月）と所定総の根拠（清算期間）が食い違う。</strong>
         *
         * <p>清算期間のほうを日額の分母に使うと、
         * 月給 30 万円・暦月 21 日の会社で 1 日欠勤したときの控除が
         * 14,285 円ではなく 27,272 円になる（労基法 24 条の全額払い・落とし穴 132）。
         * <strong>暦月の所定労働日数は他のどの列からも復元できない</strong>ので、別に渡す。
         */
        @Test
        @DisplayName("IT-PAY-71 月中入社の月は暦月と清算期間で所定労働日数が違う")
        void midMonthHireCarriesBothScheduledDays() throws Exception {
            LocalDate hiredOn = LocalDate.of(2026, 5, 18);   // 月曜
            EmployeeId newcomer = hire("E0004", hiredOn, Optional.empty(), Role.EMPLOYEE);
            series.assign(newcomer, standard, hiredOn);
            // ★ 所属は入社日より前に登録できない。固定の HIRED を使い回さない
            assignments.save(jp.co.sample.kintai.employee.domain.Assignment
                    .startingAt(newcomer, sales, hiredOn));
            for (LocalDate date = hiredOn; date.isBefore(LocalDate.of(2026, 6, 1));
                    date = date.plusDays(1)) {
                if (date.getDayOfWeek().getValue() >= 6) {
                    continue;
                }
                punch(newcomer, date, TimeClockEvent.Type.CLOCK_IN, 9);
                punch(newcomer, date, TimeClockEvent.Type.BREAK_START, 12);
                punch(newcomer, date, TimeClockEvent.Type.BREAK_END, 13);
                punch(newcomer, date, TimeClockEvent.Type.CLOCK_OUT, 18);
            }
            closeAfterWork(newcomer, MAY);
            String id = createExport();

            String[] columns = csvOf(id);
            assertThat(columns[社員番号]).isEqualTo("E0004");
            assertThat(Integer.parseInt(columns[暦月所定労働日数]))
                    .as("2026 年 5 月の平日はすべて所定労働日").isEqualTo(21);
            assertThat(Integer.parseInt(columns[清算期間所定労働日数]))
                    .as("5/18〜5/31 の平日").isEqualTo(10);
            assertThat(Integer.parseInt(columns[出勤日数])).isEqualTo(10);
            assertThat(Integer.parseInt(columns[所定総]))
                    .as("清算期間の所定労働日 10 日 × 8 時間").isEqualTo(10 * 480);
        }

        /** 全社員の賃金データなので、人事でなければ CSV そのものを取れない。 */
        @Test
        @DisplayName("IT-PAY-58 人事でない利用者は CSV を取得できない")
        void csvIsForHumanResourcesOnly() throws Exception {
            workAndClose(taro, MAY);
            String id = createExport();

            mockMvc.perform(get("/api/payroll/exports/" + id)
                            .with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }

        private String[] csvOf(String id) throws Exception {
            return rawCsvOf(id).lines().skip(1).findFirst().orElseThrow().split(",");
        }

        private String rawCsvOf(String id) throws Exception {
            return mockMvc.perform(get("/api/payroll/exports/" + id)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
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

        /**
         * <strong>別の月の記録を混ぜる。</strong>
         * 対象月の記録しか無い状態で数えると、
         * 月での絞り込みを消しても件数が変わらない（落とし穴 102）。
         */
        @Test
        @DisplayName("IT-PAY-44 同じ月を 2 回出力でき、月で絞ると 2 件になる")
        void sameMonthTwice() throws Exception {
            workAndClose(taro, MAY);
            createExport();
            createExport();
            createExport(YearMonth.of(2026, 4));   // 別の月。全員が除外されるが記録は残る

            mockMvc.perform(get("/api/payroll/exports?month=2026-05")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.exports.length()").value(2))
                    .andExpect(jsonPath("$.exports[0].month").value("2026-05"));

            mockMvc.perform(get("/api/payroll/exports")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    // 絞らなければ 4 月ぶんも含めて 3 件
                    .andExpect(jsonPath("$.exports.length()").value(3));
        }

        /** 全社員の賃金データの一覧なので、人事でなければ引けない。 */
        @Test
        @DisplayName("IT-PAY-59 人事でない利用者は記録の一覧を引けない")
        void recordsAreForHumanResourcesOnly() throws Exception {
            mockMvc.perform(get("/api/payroll/exports")
                            .with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
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

    /**
     * 出力に使った分母（労基則 19 条 1 項 4 号）の保護。
     *
     * <p><strong>止めるのは「分母が動く変更」だけである。</strong>
     * 「出力したか」で拒むと、法定休日と所定休日の付け替えのような
     * 分母を動かさない訂正まで年度いっぱい止まる。
     * 逆に、就業規則の適用（分母のもう一方の入力）は素通りする。
     */
    @Nested
    @DisplayName("分母の保護")
    class Divisor {

        @Test
        @DisplayName("IT-PAY-62 出力済みの年度でも所定労働日数が変わらない訂正は通る")
        void nonDivisorChangeIsAllowed() throws Exception {
            workAndClose(taro, MAY);
            createExport();

            // 2026-12-27 は日曜（法定休日）。所定休日へ付け替えても所定労働日数は変わらない
            mockMvc.perform(put("/api/calendars/{date}", "2026-12-27")
                            .contentType("application/json")
                            .content("{\"dayType\":\"NON_LEGAL_HOLIDAY\",\"name\":\"年末休暇\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("IT-PAY-63 出力済みの年度で所定労働日を休日に変えると 409")
        void divisorChangeIsRejected() throws Exception {
            workAndClose(taro, MAY);
            createExport();

            // 2026-12-30 は水曜（所定労働日）。休日にすると年間の所定が 1 日ぶん減る
            mockMvc.perform(put("/api/calendars/{date}", "2026-12-30")
                            .contentType("application/json")
                            .content("{\"dayType\":\"NON_LEGAL_HOLIDAY\",\"name\":\"年末休暇\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:fiscal-year-used-by-payroll"));
        }

        /**
         * <strong>一括設定でも同じ検査が働く。</strong>
         * 同じ規則に 2 つの入口があるのに片方しか通していないと、
         * 一方だけを消しても落ちない（落とし穴 111）。
         */
        @Test
        @DisplayName("IT-PAY-64 一括設定でも出力済みの年度は守られる")
        void bulkRegistrationIsGuarded() throws Exception {
            workAndClose(taro, MAY);
            createExport();

            mockMvc.perform(post("/api/calendars/bulk")
                            .contentType("application/json")
                            .content("""
                                    {"from":"2026-12-01","toExclusive":"2027-01-01",
                                     "rules":[{"dayOfWeek":"MONDAY","dayType":"NON_LEGAL_HOLIDAY"}],
                                     "overrides":[]}
                                    """)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:fiscal-year-used-by-payroll"));
        }

        /**
         * <strong>1 行も出なかった記録では凍結しない。</strong>
         * 全員が除外された記録は誰にも賃金を払っていない。
         * 数えると、動作確認で 1 回叩いただけでその年度のカレンダーを直せなくなる。
         */
        @Test
        @DisplayName("IT-PAY-65 行の出なかった出力は年度を凍結しない")
        void emptyExportDoesNotFreeze() throws Exception {
            createExport();   // 誰も締めていないので rowCount は 0

            mockMvc.perform(put("/api/calendars/{date}", "2026-12-30")
                            .contentType("application/json")
                            .content("{\"dayType\":\"NON_LEGAL_HOLIDAY\",\"name\":\"年末休暇\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isNoContent());
        }

        /** 守るのは出力した年度だけ。別の年度は自由に組める。 */
        @Test
        @DisplayName("IT-PAY-66 別の年度のカレンダーは変えられる")
        void otherFiscalYearIsUntouched() throws Exception {
            workAndClose(taro, MAY);
            createExport();

            // 2027-06-01 は FY2027。FY2026 の出力とは無関係
            mockMvc.perform(put("/api/calendars/{date}", "2027-06-01")
                            .contentType("application/json")
                            .content("{\"dayType\":\"NON_LEGAL_HOLIDAY\",\"name\":\"創立記念日\"}")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isNoContent());
        }

        /**
         * <strong>分母の入力はカレンダーだけではない。</strong>
         * 年間の所定は「所定労働日 × その日に適用されている規則の所定」なので、
         * 就業規則の適用を変えても動く（落とし穴 130）。
         */
        @Test
        @DisplayName("IT-PAY-67 出力済みの年度に所定の違う就業規則を適用すると 409")
        void applyingAnotherRuleIsGuarded() throws Exception {
            workAndClose(taro, MAY);
            createExport();

            WorkRuleSeriesId shorter = new WorkRuleSeriesId(UUID.randomUUID());
            series.save(WorkRuleSeries.active(shorter, "短時間勤務"));
            workRules.save(WorkRules.versionOf(shorter, LocalDate.of(2026, 7, 1),
                    WorkRules.fixed("09:00", "17:45", 60),
                    Duration.ofHours(8), NightWindow.STANDARD));

            mockMvc.perform(post("/api/employees/{id}/work-rule-assignments", taro.value())
                            .contentType("application/json")
                            .content("{\"seriesId\":\"%s\",\"validFrom\":\"2026-07-01\"}"
                                    .formatted(shorter.value()))
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:fiscal-year-used-by-payroll"));
        }

        /**
         * <strong>利用者が送る値の不備は 422 で返す。</strong>
         * {@code DateRange} の compact constructor に任せると、
         * 理由の載らない 500 になる（落とし穴 105）。
         */
        @Test
        @DisplayName("IT-PAY-68 一括設定の期間が逆だと 422")
        void bulkPeriodMustBeOrdered() throws Exception {
            mockMvc.perform(post("/api/calendars/bulk")
                            .contentType("application/json")
                            .content("""
                                    {"from":"2027-04-01","toExclusive":"2026-04-01",
                                     "rules":[],"overrides":[]}
                                    """)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-calendar-request"));
        }

        /** 後勝ちで畳まない。どちらを意図したのか決められない。 */
        @Test
        @DisplayName("IT-PAY-69 一括設定に同じ曜日が 2 つあると 422")
        void bulkRulesMustNotRepeatDayOfWeek() throws Exception {
            mockMvc.perform(post("/api/calendars/bulk")
                            .contentType("application/json")
                            .content("""
                                    {"from":"2028-04-01","toExclusive":"2028-05-01",
                                     "rules":[{"dayOfWeek":"MONDAY","dayType":"WORKDAY"},
                                              {"dayOfWeek":"MONDAY","dayType":"LEGAL_HOLIDAY"}],
                                     "overrides":[]}
                                    """)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-calendar-request"));
        }

        /** 期間の外の個別指定を黙って捨てると、登録したつもりの祝日が入らない。 */
        @Test
        @DisplayName("IT-PAY-70 一括設定の個別指定が期間の外だと 422")
        void bulkOverridesMustBeInsideThePeriod() throws Exception {
            mockMvc.perform(post("/api/calendars/bulk")
                            .contentType("application/json")
                            .content("""
                                    {"from":"2028-04-01","toExclusive":"2028-05-01",
                                     "rules":[],
                                     "overrides":[{"date":"2028-06-01","dayType":"LEGAL_HOLIDAY"}]}
                                    """)
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-calendar-request"));
        }
    }

    private String createExport() throws Exception {
        return createExport(MAY);
    }

    private String createExport(YearMonth month) throws Exception {
        String body = mockMvc.perform(post("/api/payroll/exports")
                        .contentType("application/json")
                        .content("{\"month\":\"%s\"}".formatted(month))
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
        workAllMonth(employeeId, month, 8);
    }

    /** 1 日の実労働時間を指定して、その月の所定労働日すべてに打刻を入れる。 */
    private void workAllMonth(EmployeeId employeeId, YearMonth month, int workingHours) {
        for (LocalDate date = month.atDay(1); date.isBefore(month.plusMonths(1).atDay(1));
                date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() >= 6) {
                continue;
            }
            punch(employeeId, date, TimeClockEvent.Type.CLOCK_IN, 9);
            punch(employeeId, date, TimeClockEvent.Type.BREAK_START, 12);
            punch(employeeId, date, TimeClockEvent.Type.BREAK_END, 13);
            punch(employeeId, date, TimeClockEvent.Type.CLOCK_OUT, 10 + workingHours);
        }
    }

    /** 打刻済みの月を 提出 → 承認 → 締め まで通す。 */
    private void closeAfterWork(EmployeeId employeeId, YearMonth month) {
        Requester self = new Requester(employeeId, Set.of(Role.EMPLOYEE));
        Requester humanResources = new Requester(hr, Set.of(Role.EMPLOYEE, Role.HR));
        Requester approver = new Requester(boss, Set.of(Role.EMPLOYEE, Role.APPROVER));
        attendances.submit(self, employeeId, month, Optional.empty(), 0L);
        Requester decider = employeeId.equals(boss) ? humanResources : approver;
        attendances.approve(decider, employeeId, month,
                attendances.currentVersion(humanResources, employeeId, month));
        attendances.close(humanResources, employeeId, month,
                attendances.currentVersion(humanResources, employeeId, month));
    }

    /** 打刻 → 提出 → 承認 → 締め まで本番の経路で通す。 */
    private void workAndClose(EmployeeId employeeId, YearMonth month) {
        // ★ 承認は部署長が行う（BR-11）。人事が承認できるのは承認者を導けない場合だけ
        workAllMonth(employeeId, month);
        closeAfterWork(employeeId, month);
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
