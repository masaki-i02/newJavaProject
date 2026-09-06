package jp.co.sample.kintai.leave.presentation;

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
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.ResultActions;

import jp.co.sample.kintai.attendance.domain.DailyAttendanceRepository;
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
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.GrantDecision;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.WebIntegrationTestBase;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.CompanyCalendar;
import jp.co.sample.kintai.workrule.domain.CompanyCalendarRepository;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleRepository;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesRepository;

/**
 * 年次有給休暇の API（IT-LV-31〜57・65〜67・78〜89・93〜96・98）。
 *
 * <p>時計は 2026-11-10 に固定する。取消の期限（取得日の前日まで）と
 * 対象月の末日の到来を検査するので、実時刻で回すと結果が実行した日で変わる。
 *
 * <p>組織は 2 つ置く。営業部（山田・課長）と販促部（部外者・別課長）である。
 * <strong>1 つだと閲覧範囲の絞りが働いているかを確かめられない。</strong>
 */
@DisplayName("年次有給休暇の API")
class PaidLeaveApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    /** 0 回目の付与日。10 日が付与されている。 */
    private static final LocalDate FIRST_GRANT = LocalDate.of(2026, 10, 1);
    /** 1 回目の付与日。まだ到来していない。 */
    private static final LocalDate SECOND_GRANT = LocalDate.of(2027, 10, 1);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    /** 未来の所定労働日（水曜）。取消の期限をまたぐ検査に使う。 */
    private static final LocalDate TOMORROW = LocalDate.of(2026, 11, 11);
    /** 10 月の所定労働日（月曜）。 */
    private static final LocalDate IN_OCTOBER = LocalDate.of(2026, 10, 5);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(10, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
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
    private CompanyCalendarRepository calendarRepository;
    @Autowired
    private CompanyCalendar calendar;
    @Autowired
    private DailyAttendanceRepository dailyAttendances;
    @Autowired
    private PaidLeaveGrantRepository grants;

    private EmployeeId yamada;
    private EmployeeId manager;
    private EmployeeId outsider;
    private EmployeeId otherManager;
    private EmployeeId hr;

    @BeforeEach
    void setUpOrganization() {
        yamada = hire("E0001", "山田 太郎", Optional.empty());
        manager = hire("E0100", "課長 次郎", Optional.empty());
        outsider = hire("E0200", "他部署 三郎", Optional.empty());
        otherManager = hire("E0300", "別課長 四郎", Optional.empty());
        hr = hire("E0900", "人事 花子", Optional.empty());

        var sales = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(sales, new DepartmentCode("SALES"), "営業部"));
        assignments.save(Assignment.startingAt(yamada, sales, HIRED));
        assignments.save(Assignment.startingAt(manager, sales, HIRED));
        managerships.save(Managership.startingAt(sales, manager, HIRED));

        var marketing = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(marketing, new DepartmentCode("MKT"), "販促部"));
        assignments.save(Assignment.startingAt(outsider, marketing, HIRED));
        assignments.save(Assignment.startingAt(otherManager, marketing, HIRED));
        managerships.save(Managership.startingAt(marketing, otherManager, HIRED));

        var standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(yamada, standard, HIRED);
        series.assign(outsider, standard, HIRED);

        // 10〜12 月の土日を休日にする。平日は未登録のまま所定労働日（本番と同じ既定）
        for (LocalDate date = OCTOBER.atDay(1);
                date.isBefore(LocalDate.of(2028, 1, 1)); date = date.plusDays(1)) {
            switch (date.getDayOfWeek()) {
                case SUNDAY -> calendarRepository.save(date, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> calendarRepository.save(date, DayType.NON_LEGAL_HOLIDAY,
                        "所定休日");
                default -> { }
            }
        }
        grantTenDays(yamada);
    }

    @Nested
    @DisplayName("残日数の参照")
    class Balance {

        @Test
        @DisplayName("IT-LV-31 本人が残日数を見る")
        void own() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingDays").value(10))
                    .andExpect(jsonPath("$.asOf").value("2026-11-10"))
                    .andExpect(jsonPath("$.grants[0].grantedOn").value("2026-10-01"))
                    .andExpect(jsonPath("$.grants[0].expiresOn").value("2028-10-01"))
                    .andExpect(jsonPath("$.grants[0].usedDays").value(0));
        }

        /** 未処理の申請も差し引く。承認済みだけを引くと残 1 日に 2 件が通る。 */
        /**
         * <strong>不付与の年も要素として返す。</strong>
         * 行が無いのではなく「付与しなかった」ので、出勤率とともに残す。
         * 意味を持たない {@code days} / {@code usedDays} / {@code remainingDays} は
         * <strong>項目ごと</strong>省く。0 を返すと「0 日付与された」と読める。
         * record 全体に {@code @JsonInclude} を付けると出勤率の
         * {@code deemedReason: null} まで消え、「申告が無い」ことが読めなくなる（落とし穴 76）。
         */
        @Test
        @DisplayName("IT-LV-129 不付与の年も、日数の項目を省いて返す")
        void withheldGrantIsListed() throws Exception {
            grants.save(new PaidLeaveGrant(PaidLeaveGrantId.generate(), yamada, 1,
                    LocalDate.of(2027, 10, 1), AttendanceRate.of(240, 100),
                    new GrantDecision.Withheld(),
                    LocalDate.of(2027, 10, 1).atStartOfDay(), 1L));

            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grants[1].granted").value(false))
                    .andExpect(jsonPath("$.grants[1].days").doesNotExist())
                    .andExpect(jsonPath("$.grants[1].usedDays").doesNotExist())
                    .andExpect(jsonPath("$.grants[1].remainingDays").doesNotExist())
                    .andExpect(jsonPath("$.grants[1].attendanceRate.attendedDays").value(100))
                    // ★ 出勤扱いの申告が無いことは、null として読み取れなければならない。
                    //   項目ごと消えると「申告が無い」と「この API では返さない」を区別できない
                    .andExpect(jsonPath("$.grants[1].attendanceRate.deemedReason")
                            .hasJsonPath());
        }

        @Test
        @DisplayName("IT-LV-32 未処理の申請が availableDays から引かれる")
        void pendingReducesAvailable() throws Exception {
            // ★ 前後で見る。「remainingDays > availableDays」は関係として成り立たない。
            //   availableDays は申請できる期間に入る未到来の付与も数えるので、
            //   保有日数より大きくなりうる（IT-LV-98）
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(jsonPath("$.availableDays").value(21));

            submit(yamada, TOMORROW).andExpect(status().isCreated());

            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    // 未処理の申請は保有日数を動かさない
                    .andExpect(jsonPath("$.remainingDays").value(10))
                    .andExpect(jsonPath("$.availableDays").value(20));
        }

        /**
         * <strong>未到来の付与も数える</strong>（落とし穴 96・103）。
         *
         * <p>受理判定は取得日の時点で有効な付与を探すので、基準日で絞ると
         * 「0 日と表示されるのに申請は通る」という食い違いが起きる。
         */
        @Test
        @DisplayName("IT-LV-98 未到来の付与が availableDays にも数えられる")
        void scheduledGrantsAreCounted() throws Exception {
            // 1 回目の付与（2027-10-01・11 日）は申請できる期間（当日 + 1 年）に入る
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingDays")
                            .value(10))
                    .andExpect(jsonPath("$.availableDays").value(21));

            // 表示どおり、次の付与日より後の日程も申請できる
            submit(yamada, SECOND_GRANT.plusDays(14)).andExpect(status().isCreated());
        }

        /**
         * <strong>{@code APPROVER} を持つ他部署の長で試す。</strong>
         * ロールを持たない社員で試すと、`EmployeeVisibility` はロールを見た時点で弾くので、
         * <strong>「配下部署か」を辿る処理を消しても落ちない</strong>（落とし穴 12・24）。
         * IT-LV-34 とは所属部署だけが違う。
         */
        @Test
        @DisplayName("IT-LV-33 配下でない社員の残日数は見られない")
        void outOfScope() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(otherManager, "E0300", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }

        @Test
        @DisplayName("IT-LV-34 承認者は配下の社員の残日数を見られる")
        void approverCanView() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingDays").value(10));
        }

        /** 認証がまだなら 401。302 でログイン画面へ飛ばさない（要件 4）。 */
        @Test
        @DisplayName("IT-LV-67 未認証では参照できない")
        void unauthenticated() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("申請の参照")
    class Listing {

        /** 決裁した結果が永続化されていることを、HTTP から読み戻して確かめる。 */
        @Test
        @DisplayName("IT-LV-130 決裁した申請を読み戻せる")
        void readsBackAfterDecision() throws Exception {
            String id = submitAndGetId(TOMORROW);
            approve(manager, id, 1L).andExpect(status().isOk());

            mockMvc.perform(get("/api/paid-leave-requests/{id}", id)
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.leaveDate").value("2026-11-11"))
                    .andExpect(jsonPath("$.version").value(2));
        }

        /** 閲覧範囲は 1 件の参照にも効く（要件 4.1）。 */
        @Test
        @DisplayName("IT-LV-131 配下でない社員の申請は 1 件でも読めない")
        void detailIsScoped() throws Exception {
            String id = submitAndGetId(TOMORROW);

            mockMvc.perform(get("/api/paid-leave-requests/{id}", id)
                            .with(as(otherManager, "E0300", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }

        @Test
        @DisplayName("IT-LV-132 その社員の申請の一覧を承認者が見る")
        void listOfEmployee() throws Exception {
            submitAndGetId(TOMORROW);

            mockMvc.perform(get("/api/employees/{id}/paid-leave-requests", yamada.value())
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].leaveDate").value("2026-11-11"));
        }

        /** 絞らないと、他部署の長が配下でない社員の申請を読める。 */
        @Test
        @DisplayName("IT-LV-133 配下でない社員の申請は一覧に出ない")
        void listIsScoped() throws Exception {
            submitAndGetId(TOMORROW);

            mockMvc.perform(get("/api/employees/{id}/paid-leave-requests", yamada.value())
                            .with(as(otherManager, "E0300", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        /**
         * 承認待ちの一覧。<strong>版は載せない</strong>（API設計書 1.1）。
         * 行ごとに引くと社員数ぶんの問い合わせが増え、版が要るのは決裁する 1 件だけである。
         */
        @Test
        @DisplayName("IT-LV-134 承認待ちの一覧は配下の社員だけを返し、版を載せない")
        void pendingApprovalIsScoped() throws Exception {
            submitAndGetId(TOMORROW);
            grantTenDays(outsider);
            submit(outsider, TOMORROW).andExpect(status().isCreated());

            mockMvc.perform(get("/api/paid-leave-requests/pending-approval")
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].employeeId").value(yamada.value().toString()))
                    .andExpect(jsonPath("$[0].version").doesNotExist());
        }

        /** 決裁済みは承認待ちに残らない。 */
        @Test
        @DisplayName("IT-LV-135 決裁した申請は承認待ちの一覧から消える")
        void decidedLeavesThePendingList() throws Exception {
            String id = submitAndGetId(TOMORROW);
            approve(manager, id, 1L).andExpect(status().isOk());

            mockMvc.perform(get("/api/paid-leave-requests/pending-approval")
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("申請")
    class Submission {

        @Test
        @DisplayName("IT-LV-35 本人が申請する")
        void submits() throws Exception {
            submit(yamada, TOMORROW)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("SUBMITTED"))
                    .andExpect(jsonPath("$.leaveDate").value("2026-11-11"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        /** 代理申請を認めない。時季指定は本人の意思表示である。 */
        @Test
        @DisplayName("IT-LV-36 他人の年休は代理で申請できない")
        void proxyByOther() throws Exception {
            mockMvc.perform(post("/api/employees/{id}/paid-leave-requests", yamada.value())
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submitBody(TOMORROW)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:not-the-requester"));
        }

        /** 人事でも代わりには出せない（訂正申請と同じ判断）。 */
        @Test
        @DisplayName("IT-LV-37 人事も代理では申請できない")
        void proxyByHumanResources() throws Exception {
            mockMvc.perform(post("/api/employees/{id}/paid-leave-requests", yamada.value())
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submitBody(TOMORROW)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:not-the-requester"));
        }

        @Test
        @DisplayName("IT-LV-38 残日数を超えると申請できない")
        void insufficient() throws Exception {
            LocalDate date = TOMORROW;
            for (int i = 0; i < 10; i++) {
                submit(yamada, date).andExpect(status().isCreated());
                date = nextWorkdayAfter(date);
            }

            submit(yamada, date)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:insufficient-paid-leave"));
        }

        @Test
        @DisplayName("IT-LV-39 所定休日は指定できない")
        void notAWorkday() throws Exception {
            submit(yamada, LocalDate.of(2026, 11, 14))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:not-a-workday"));
        }

        @Test
        @DisplayName("IT-LV-40 退職後の日は指定できない")
        void notInService() throws Exception {
            // ★ 境界で見る。退職日当日は在籍、翌日は非在籍（半開区間・落とし穴 10）
            retire(yamada, LocalDate.of(2026, 11, 30));

            submit(yamada, LocalDate.of(2026, 12, 1))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:leave-date-not-in-service"));
        }

        @Test
        @DisplayName("IT-LV-41 同じ日に 2 件目は申請できない")
        void duplicate() throws Exception {
            submit(yamada, TOMORROW).andExpect(status().isCreated());

            submit(yamada, TOMORROW)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:duplicate-leave-request"));
        }

        @Test
        @DisplayName("IT-LV-42 締め済みの月には申請できない")
        void closedMonth() throws Exception {
            closeOctober();

            submit(yamada, IN_OCTOBER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-already-closed"));
        }

        /** 締め済みと別のエラーにする。承認済みは承認を取り消せば直せる。 */
        @Test
        @DisplayName("IT-LV-43 承認済みの月には申請できない")
        void approvedMonth() throws Exception {
            approveOctober();

            submit(yamada, IN_OCTOBER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-not-editable"));
        }

        /**
         * 付与の行は到来したぶんしか作られない。組み入れないと、
         * <strong>次の付与日の直後の日程を付与日が来るまで誰も申請できない。</strong>
         */
        @Test
        @DisplayName("IT-LV-86 未到来の付与日以降の日程も申請できる")
        void afterNextGrantDate() throws Exception {
            submit(yamada, SECOND_GRANT.plusDays(14))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.leaveDate").value("2027-10-15"));
        }
    }

    @Nested
    @DisplayName("承認")
    class Approval {

        @Test
        @DisplayName("IT-LV-44 承認者が承認すると配分先が返る")
        void approves() throws Exception {
            String id = submitAndGetId(TOMORROW);

            approve(manager, id, 1L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("APPROVED"))
                    .andExpect(jsonPath("$.request.grantedOn").value("2026-10-01"))
                    .andExpect(jsonPath("$.request.version").value(2));
        }

        /**
         * <strong>{@code not-approver} にまとめない。</strong>
         * まとめると、自己承認の禁止を消してもテストが 1 件も落ちない（落とし穴 58）。
         */
        @Test
        @DisplayName("IT-LV-45 自分の申請は自分で承認できない")
        void selfApproval() throws Exception {
            String id = submitAndGetId(TOMORROW);

            approve(yamada, id, 1L)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:self-approval"));
        }

        @Test
        @DisplayName("IT-LV-46 承認者でない社員は承認できない")
        void notApprover() throws Exception {
            String id = submitAndGetId(TOMORROW);

            mockMvc.perform(post("/api/paid-leave-requests/{id}/approval", id)
                            .with(as(outsider, "E0200", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:not-approver"));
        }

        /**
         * <strong>認可を業務検査より先に置く</strong>（要件 4.1）。
         *
         * <p>あとに置くと、承認者でない社員が申請 ID を持っているだけで
         * 対象社員の締め状態・所定労働日・在籍・実労働の有無・残日数を
         * エラーの型から読み取れる。ここでは<strong>締め済みの月</strong>で試す。
         * 順序が逆なら `month-already-closed` が返り、その事実が漏れる。
         */
        @Test
        @DisplayName("IT-LV-125 承認者でない社員には、対象月の状態より先に not-approver を返す")
        void authorizationComesFirst() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            closeOctober();

            mockMvc.perform(post("/api/paid-leave-requests/{id}/approval", id)
                            .with(as(outsider, "E0200", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":1}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:not-approver"));
        }

        @Test
        @DisplayName("IT-LV-47 古い version では承認できない")
        void staleVersion() throws Exception {
            String id = submitAndGetId(TOMORROW);

            approve(manager, id, 0L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));
        }

        @Test
        @DisplayName("IT-LV-48 決裁済みの申請は再び承認できない")
        void alreadyDecided() throws Exception {
            String id = submitAndGetId(TOMORROW);
            approve(manager, id, 1L).andExpect(status().isOk());

            approve(manager, id, 2L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:invalid-transition"));
        }

        /**
         * 承認済みの月で年休を承認すると、月次清算だけが変わり
         * <strong>「提出済みなら下書きへ戻す」が働かない。</strong>
         * 承認者が見た内容と締めで確定する内容が黙って食い違う。
         */
        @Test
        @DisplayName("IT-LV-79 承認済みの月の年休は承認できない")
        void approvedMonth() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approveOctober();

            approve(manager, id, 1L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-not-editable"));
        }

        /** 申請は仮の付与で受け付けるが、配分できるのは行のある付与だけである。 */
        @Test
        @DisplayName("IT-LV-87 付与が実体化していない日は承認できない")
        void grantNotYetIssued() throws Exception {
            // ★ 付与の行が 1 件も無い社員で試す。山田には 2026-10-01 の付与があり、
            //   それは 2028-10-01 まで有効なので、どの取得日でも配分できてしまう
            String body = submit(outsider, TOMORROW).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String id = objectMapper.readTree(body).get("id").asString();

            approve(otherManager, id, 1L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:grant-not-yet-issued"));
        }

        /**
         * すでに働いた日の年休を通すと、所定総からその日が除かれるのに実労働もあるので、
         * <strong>不足時間が過少に出る一方で社員は年休を 1 日失う</strong>（落とし穴 97）。
         */
        @Test
        @DisplayName("IT-LV-96 すでに実労働がある日の年休は承認できない")
        void alreadyWorked() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            workedOn(IN_OCTOBER);

            approve(manager, id, 1L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:leave-date-already-worked"));
        }
    }

    @Nested
    @DisplayName("却下")
    class Rejection {

        @Test
        @DisplayName("IT-LV-49 承認者が理由を付けて却下する")
        void rejects() throws Exception {
            String id = submitAndGetId(TOMORROW);

            reject(manager, id, "その週は繁忙のため別日でお願いします", 1L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("REJECTED"))
                    .andExpect(jsonPath("$.request.comment")
                            .value("その週は繁忙のため別日でお願いします"));
        }

        /** 理由なしの却下は、本人が次に何をすればよいか分からない。 */
        @Test
        @DisplayName("IT-LV-50 理由の無い却下は受け付けない")
        void commentRequired() throws Exception {
            String id = submitAndGetId(TOMORROW);

            reject(manager, id, "   ", 1L)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:validation-failed"));
        }

        /**
         * 却下は残日数も月次清算も動かさないので、締め済みでも通す。
         * 拒否すると<strong>どの状態にも遷移できない申請</strong>が残るだけである。
         */
        @Test
        @DisplayName("IT-LV-81 締め済みの月でも却下できる")
        void closedMonth() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            closeOctober();

            reject(manager, id, "締め後に判明したため", 1L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("REJECTED"));
        }

        @Test
        @DisplayName("IT-LV-88 古い version では却下できない")
        void staleVersion() throws Exception {
            String id = submitAndGetId(TOMORROW);

            reject(manager, id, "理由", 0L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));
        }
    }

    @Nested
    @DisplayName("取下げ")
    class Cancellation {

        @Test
        @DisplayName("IT-LV-51 承認済みを取得日の前日に取り下げると残日数が戻る")
        void cancelApproved() throws Exception {
            String id = submitAndGetId(TOMORROW);
            approve(manager, id, 1L).andExpect(status().isOk());

            // ★ 承認で 9 に減ったことを先に見る。取消後の 10 だけを見ると、
            //   「承認しても減らない」実装と区別がつかない（落とし穴 36）
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(jsonPath("$.remainingDays").value(9))
                    .andExpect(jsonPath("$.grants[0].usedDays").value(1));

            cancel(yamada, id, 2L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("CANCELED"));

            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(jsonPath("$.remainingDays").value(10))
                    .andExpect(jsonPath("$.grants[0].usedDays").value(0));
        }

        /** 当日以降は実績が確定している。人事の取消（3.5）へ案内する。 */
        @Test
        @DisplayName("IT-LV-52 承認済みを取得日の当日には取り下げられない")
        void cancelOnTheDay() throws Exception {
            String id = submitAndGetId(TODAY);
            approve(manager, id, 1L).andExpect(status().isOk());

            cancel(yamada, id, 2L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:leave-not-cancelable"));
        }

        @Test
        @DisplayName("IT-LV-53 他人の申請は取り下げられない")
        void byOther() throws Exception {
            String id = submitAndGetId(TOMORROW);

            cancel(manager, id, 1L)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:not-the-requester"));
        }

        /**
         * <strong>本人かどうかを最初に見る</strong>（要件 4.1）。
         *
         * <p>締め済みの月の承認済み年休を他人が取り消そうとしたとき、
         * 順序が逆なら `month-already-closed` が返り、対象社員の月の状態が漏れる。
         */
        @Test
        @DisplayName("IT-LV-126 他人の取下げには、対象月の状態より先に not-the-requester を返す")
        void requesterCheckComesFirst() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approve(manager, id, 1L).andExpect(status().isOk());
            closeOctober();

            cancel(manager, id, 2L)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:not-the-requester"));
        }

        /**
         * 申請中に期限を設けると、承認者が決裁しないまま取得日と月末が過ぎた申請が
         * <strong>どの状態にも遷移できなくなる</strong>（落とし穴 93）。
         */
        @Test
        @DisplayName("IT-LV-80 申請中は取得日を過ぎても本人が取り下げられる")
        void cancelPendingAfterLeaveDate() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);

            cancel(yamada, id, 1L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("CANCELED"));
        }

        /** 申請中の取下げは残日数も月次清算も動かさないので、月の状態を問わない。 */
        @Test
        @DisplayName("IT-LV-94 締め済みの月でも申請中の年休は取り下げられる")
        void cancelPendingInClosedMonth() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            closeOctober();

            cancel(yamada, id, 1L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("CANCELED"));
        }

        @Test
        @DisplayName("IT-LV-89 古い version では取り下げられない")
        void staleVersion() throws Exception {
            String id = submitAndGetId(TOMORROW);

            cancel(yamada, id, 0L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));
        }
    }

    @Nested
    @DisplayName("人事による取消")
    class Revocation {

        @Test
        @DisplayName("IT-LV-82 取得日の当日以降、人事が理由を付けて取り消す")
        void revokes() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approve(manager, id, 1L).andExpect(status().isOk());

            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(jsonPath("$.remainingDays").value(9));

            revoke(hr, id, "予定を変更して出勤したため", 2L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("CANCELED"));

            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(jsonPath("$.remainingDays").value(10));
        }

        /** 実績が確定した日を本人が動かせてはいけない。 */
        @Test
        @DisplayName("IT-LV-83 人事以外は取り消せない")
        void notHumanResources() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approve(manager, id, 1L).andExpect(status().isOk());

            revoke(manager, id, "理由", 2L)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }

        @Test
        @DisplayName("IT-LV-84 理由の無い取消は受け付けない")
        void commentRequired() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approve(manager, id, 1L).andExpect(status().isOk());

            revoke(hr, id, "  ", 2L)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:validation-failed"));
        }

        /**
         * 承認済みの月では取り消せない（落とし穴 26 と同型の食い違いを避ける）。
         * 締め済みとは別のエラーにする。承認を取り消せば直せるからである。
         */
        @Test
        @DisplayName("IT-LV-95 承認済みの月では承認済みの年休を取り消せない")
        void approvedMonth() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approve(manager, id, 1L).andExpect(status().isOk());
            approveOctober();

            revoke(hr, id, "予定を変更して出勤したため", 2L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:month-not-editable"));
        }
    }

    @Nested
    @DisplayName("月次との連動")
    class MonthlyLink {

        @Test
        @DisplayName("IT-LV-54 提出済みの月の年休を承認すると下書きへ戻る")
        void revertsToDraft() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            submitOctober();

            approve(manager, id, 1L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.monthlyAttendanceStatus").value("DRAFT"));
        }

        /**
         * <strong>{@code REVERT_BY_CORRECTION} を流用しない。</strong>
         * 何が原因で戻ったのかが証跡から読めなくなる。
         */
        @Test
        @DisplayName("IT-LV-55 その差戻しが REVERT_BY_LEAVE として記録される")
        void auditTrail() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            submitOctober();
            approve(manager, id, 1L).andExpect(status().isOk());

            assertThat(jdbc.queryForList(
                    "SELECT event_kind FROM approval_events ORDER BY created_at",
                    String.class))
                    .containsExactly("SUBMIT", "REVERT_BY_LEAVE");
        }

        /** 年休の日は所定労働日数から除く（BR-05）。除かないと不足時間が立つ。 */
        @Test
        @DisplayName("IT-LV-56 承認すると月次清算の所定総が 8 時間減る")
        void scheduledTotalDecreases() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            submitOctober();
            // 10 月の平日は 22 日。年休 1 日を除くと 21 日ぶんになる
            assertThat(scheduledTotalMinutes()).isEqualTo(22 * 8 * 60);

            approve(manager, id, 1L).andExpect(status().isOk());

            assertThat(scheduledTotalMinutes()).isEqualTo(21 * 8 * 60);
        }

        /**
         * 承認と対称。戻さないと不足時間が 8 時間ぶん過少に出たままになる。
         *
         * <p><strong>本人の取下げ（{@code cancellation}）で見る。</strong>
         * 人事の取消（{@code revocation}）は IT-LV-85 が持つ。
         * 両方を revoke で書くと、`cancel` の「承認済みなら副作用を起こす」分岐を
         * 1 件も通らないまま両方が緑になる。
         *
         * <p>取得日は未来にする。承認済みを本人が取り下げられるのは取得日の前日までなので、
         * 過去日で書くと `leave-not-cancelable` に化けて所定総まで到達しない。
         */
        @Test
        @DisplayName("IT-LV-57 本人が取り下げると所定総が戻る")
        void scheduledTotalRestored() throws Exception {
            String id = submitAndGetId(TOMORROW);
            approve(manager, id, 1L).andExpect(status().isOk());
            // 11 月の平日は 21 日。年休 1 日を除くと 20 日ぶん
            assertThat(novemberScheduledTotalMinutes()).isEqualTo(20 * 8 * 60);

            cancel(yamada, id, 2L).andExpect(status().isOk());

            assertThat(novemberScheduledTotalMinutes()).isEqualTo(21 * 8 * 60);
        }

        /** IT-LV-85 は IT-LV-57 と同じ経路を、残日数の側から見る。 */
        @Test
        @DisplayName("IT-LV-85 人事が取り消すと所定総が戻り、残日数も戻る")
        void revocationRestoresBoth() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approve(manager, id, 1L).andExpect(status().isOk());

            revoke(hr, id, "予定を変更して出勤したため", 2L).andExpect(status().isOk());

            assertThat(scheduledTotalMinutes()).isEqualTo(22 * 8 * 60);
            mockMvc.perform(get("/api/employees/{id}/paid-leave", yamada.value())
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(jsonPath("$.remainingDays").value(10));
        }

        /**
         * <strong>年休の日は「未確定」ではない</strong>（落とし穴 90）。
         * 日次勤怠の行が無いのは打刻が無いからであり、確定させる手段は存在しない。
         */
        @Test
        @DisplayName("IT-LV-93 年休を取った月を提出できる")
        void submitsMonthWithLeave() throws Exception {
            String id = submitAndGetId(IN_OCTOBER);
            approve(manager, id, 1L).andExpect(status().isOk());

            submitOctober();
        }
    }

    @Nested
    @DisplayName("年 5 日の取得義務")
    class Obligations {

        @Test
        @DisplayName("IT-LV-65 未達の社員の不足日数と期限が返る")
        void shortfallOnly() throws Exception {
            mockMvc.perform(get("/api/paid-leave/obligations")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.asOf").value("2026-11-10"))
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')].shortfallDays"
                            .formatted(yamada.value())).value(5))
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')].deadline"
                            .formatted(yamada.value())).value("2027-09-30"))
                    // ★ 数える先は deadline（閉区間の最終日）。
                    //   半開区間の上限まで数えると 1 日多くなる（落とし穴 10）
                    .andExpect(jsonPath(
                            "$.items[?(@.employeeId=='%s')].remainingDaysUntilDeadline"
                                    .formatted(yamada.value())).value(324));
        }

        /** 絞らないと、一般の承認者が配下でない社員の取得状況を見られる（要件 4.1）。 */
        @Test
        @DisplayName("IT-LV-78 未達一覧に配下でない社員は現れない")
        void scopedByVisibility() throws Exception {
            grantTenDays(outsider);

            mockMvc.perform(get("/api/paid-leave/obligations")
                            .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')]"
                            .formatted(yamada.value())).isNotEmpty())
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')]"
                            .formatted(outsider.value())).isEmpty());
        }

        /**
         * <strong>期限が過ぎた義務は出さない。</strong>
         * 義務期間は付与日から 1 年（社員ごとに違う）。過ぎたものはもう是正できないので、
         * 「あと何日取らせるか」の材料にならない。
         */
        @Test
        @DisplayName("IT-LV-123 期限の過ぎた義務は未達一覧に現れない")
        void expiredObligationIsNotListed() throws Exception {
            // ★ 入社日も整合させる。2026-04-01 入社の社員に 2025-10-01 の付与を置くと、
            //   本番では決して現れない行になる（落とし穴 56）
            EmployeeId veteran = hire("E0400", "古参 五郎", LocalDate.of(2025, 4, 1),
                    Optional.empty());
            // 義務期間は [2025-10-01, 2026-10-01)。今日（2026-11-10）は入っていない
            grants.save(new PaidLeaveGrant(PaidLeaveGrantId.generate(), veteran, 0,
                    LocalDate.of(2025, 10, 1), AttendanceRate.of(240, 240),
                    new GrantDecision.Granted(10),
                    LocalDate.of(2025, 10, 1).atStartOfDay(), 1L));

            mockMvc.perform(get("/api/paid-leave/obligations")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')]"
                            .formatted(veteran.value())).isEmpty());
        }

        /**
         * {@code onlyShortfall=false} なら充足している社員も返す。
         *
         * <p>既定で絞るのは、人事が見たいのが「あと何日必要な人がいるか」だからである。
         * 充足を確かめたい場面もあるので、切り替えられるようにする。
         */
        @Test
        @DisplayName("IT-LV-124 5 日取得済みの社員は onlyShortfall で絞られる")
        void fulfilledIsFilteredOut() throws Exception {
            LocalDate date = TOMORROW;
            for (int i = 0; i < 5; i++) {
                String id = submitAndGetId(date);
                approve(manager, id, 1L).andExpect(status().isOk());
                date = nextWorkdayAfter(date);
            }

            mockMvc.perform(get("/api/paid-leave/obligations")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')]"
                            .formatted(yamada.value())).isEmpty());

            mockMvc.perform(get("/api/paid-leave/obligations")
                            .param("onlyShortfall", "false")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')].takenDays"
                            .formatted(yamada.value())).value(5))
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')].shortfallDays"
                            .formatted(yamada.value())).value(0));
        }

        /** 未達は是正すべき事実だが、記録と手続きを止める理由にはならない（BR-17）。 */
        @Test
        @DisplayName("IT-LV-66 年 5 日が未達でも提出・承認・締めが通る")
        void doesNotBlockTheMonth() throws Exception {
            // ★ 未達であることを先に確かめる。確かめないと、義務の判定が壊れて
            //   未達でなくなっても「締めが通る」としか言えない
            mockMvc.perform(get("/api/paid-leave/obligations")
                            .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR)))
                    .andExpect(jsonPath("$.items[?(@.employeeId=='%s')].shortfallDays"
                            .formatted(yamada.value())).value(5));

            closeOctober();
        }

        /**
         * <strong>一般社員でも自分のぶんは見られる。</strong>
         * 閲覧範囲で絞るので他人は出ない。ロールで一律に拒むと、
         * 自分があと何日取る必要があるかを本人が確かめられなくなる。
         */
        @Test
        @DisplayName("IT-LV-136 一般社員には自分の義務だけが返る")
        void employeeSeesOnlyOwn() throws Exception {
            grantTenDays(outsider);

            mockMvc.perform(get("/api/paid-leave/obligations")
                            .with(as(yamada, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].employeeId")
                            .value(yamada.value().toString()));
        }
    }

    // ---- 以下は前提を組み立てるヘルパ ----

    private ResultActions submit(EmployeeId employeeId, LocalDate leaveDate)
            throws Exception {
        return mockMvc.perform(
                post("/api/employees/{id}/paid-leave-requests", employeeId.value())
                        .with(as(employeeId, numberOf(employeeId), Role.EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(leaveDate)));
    }

    private static String submitBody(LocalDate leaveDate) {
        return "{\"leaveDate\":\"%s\",\"reason\":\"私用のため\"}".formatted(leaveDate);
    }

    private String submitAndGetId(LocalDate leaveDate) throws Exception {
        String body = submit(yamada, leaveDate).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }

    private ResultActions approve(EmployeeId actor, String id, long version)
            throws Exception {
        return mockMvc.perform(post("/api/paid-leave-requests/{id}/approval", id)
                .with(as(actor, numberOf(actor), rolesOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":%d}".formatted(version)));
    }

    private ResultActions reject(EmployeeId actor, String id, String comment, long version)
            throws Exception {
        return mockMvc.perform(post("/api/paid-leave-requests/{id}/rejection", id)
                .with(as(actor, numberOf(actor), rolesOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"%s\",\"version\":%d}".formatted(comment, version)));
    }

    private ResultActions cancel(EmployeeId actor, String id, long version)
            throws Exception {
        return mockMvc.perform(post("/api/paid-leave-requests/{id}/cancellation", id)
                .with(as(actor, numberOf(actor), rolesOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":%d}".formatted(version)));
    }

    private ResultActions revoke(EmployeeId actor, String id, String comment, long version)
            throws Exception {
        return mockMvc.perform(post("/api/paid-leave-requests/{id}/revocation", id)
                .with(as(actor, numberOf(actor), rolesOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"%s\",\"version\":%d}".formatted(comment, version)));
    }

    private String numberOf(EmployeeId id) {
        if (id.equals(yamada)) {
            return "E0001";
        }
        if (id.equals(manager)) {
            return "E0100";
        }
        if (id.equals(outsider)) {
            return "E0200";
        }
        if (id.equals(otherManager)) {
            return "E0300";
        }
        return "E0900";
    }

    private Role[] rolesOf(EmployeeId id) {
        if (id.equals(manager) || id.equals(otherManager)) {
            return new Role[] {Role.EMPLOYEE, Role.APPROVER};
        }
        if (id.equals(hr)) {
            return new Role[] {Role.EMPLOYEE, Role.HR};
        }
        return new Role[] {Role.EMPLOYEE};
    }

    /** 10 月を提出する（本人）。 */
    private void submitOctober() throws Exception {
        mockMvc.perform(post("/api/employees/{id}/monthly-attendances/{month}/submission",
                        yamada.value(), "2026-10")
                        .with(as(yamada, "E0001", Role.EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d}".formatted(monthlyVersion())))
                .andExpect(status().isOk());
    }

    /** 10 月を提出して承認する。 */
    private void approveOctober() throws Exception {
        submitOctober();
        mockMvc.perform(post("/api/employees/{id}/monthly-attendances/{month}/approval",
                        yamada.value(), "2026-10")
                        .with(as(manager, "E0100", Role.EMPLOYEE, Role.APPROVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d}".formatted(monthlyVersion())))
                .andExpect(status().isOk());
    }

    /** 10 月を締める（人事）。 */
    private void closeOctober() throws Exception {
        approveOctober();
        mockMvc.perform(post("/api/employees/{id}/monthly-attendances/{month}/closure",
                        yamada.value(), "2026-10")
                        .with(as(hr, "E0900", Role.EMPLOYEE, Role.HR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d}".formatted(monthlyVersion())))
                .andExpect(status().isOk());
    }

    private long monthlyVersion() {
        List<Long> found = jdbc.queryForList("""
                SELECT version FROM monthly_attendances
                WHERE employee_id = ? AND target_month = '2026-10-01'
                """, Long.class, yamada.value());
        return found.isEmpty() ? 0L : found.getFirst();
    }

    private int novemberScheduledTotalMinutes() {
        return jdbc.queryForObject("""
                SELECT scheduled_total_minutes FROM monthly_settlements
                WHERE employee_id = ? AND target_month = '2026-11-01'
                """, Integer.class, yamada.value());
    }

    private int scheduledTotalMinutes() {
        return jdbc.queryForObject("""
                SELECT scheduled_total_minutes FROM monthly_settlements
                WHERE employee_id = ? AND target_month = '2026-10-01'
                """, Integer.class, yamada.value());
    }

    /** 0 回目の付与（10 日）を実体化する。 */
    private void grantTenDays(EmployeeId employeeId) {
        grants.save(new PaidLeaveGrant(PaidLeaveGrantId.generate(), employeeId, 0,
                FIRST_GRANT, AttendanceRate.of(120, 120), new GrantDecision.Granted(10),
                FIRST_GRANT.atStartOfDay(), 1L));
    }

    /** その日に 8 時間働いた事実を残す。本番の計算を通して作る（落とし穴 37）。 */
    private void workedOn(LocalDate date) {
        WorkRule rule = workRules.findEffective(yamada, date).orElseThrow();
        dailyAttendances.save(yamada,
                new DailyAttendances(calendar).fixedDay(date, Duration.ofHours(8)),
                rule.id());
    }

    private EmployeeId hire(String number, String name, Optional<LocalDate> retiredOn) {
        return hire(number, name, HIRED, retiredOn);
    }

    private EmployeeId hire(String number, String name, LocalDate hiredOn,
                            Optional<LocalDate> retiredOn) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), hiredOn, retiredOn,
                Set.of(Role.EMPLOYEE)));
        return id;
    }

    private void retire(EmployeeId employeeId, LocalDate lastDay) {
        Employee employee = employees.findById(employeeId).orElseThrow();
        employees.save(employee.retire(lastDay));
    }

    private LocalDate nextWorkdayAfter(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (calendar.dayTypeOf(next) != DayType.WORKDAY) {
            next = next.plusDays(1);
        }
        return next;
    }
}
