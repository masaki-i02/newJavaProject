package jp.co.sample.kintai.leave.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
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
import jp.co.sample.kintai.approval.domain.AttendanceState;
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
import jp.co.sample.kintai.leave.domain.LeaveRequestStatus;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantId;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrantRepository;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.DailyAttendances;
import jp.co.sample.kintai.support.IntegrationTestBase;
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
 * 年次有給休暇の申請・承認・取消（BR-16）。
 *
 * <p><strong>アプリケーション層を通して確かめる観点だけを置く。</strong>
 * 状態遷移そのものは {@code PaidLeaveRequestTest} が集約に直接あてている。
 * ここで見るのは、複数のリポジトリと他コンテキストをまたぐ規則
 * （残日数・付与の実体化・対象月の状態・実労働の有無）である。
 *
 * <p>時計は 2026-11-10 に固定する。
 * 「取得日を過ぎたか」「対象月が終わったか」を検査するので、
 * 実時刻で回すと結果が実行した日で変わる。
 */
@DisplayName("年次有給休暇の申請・承認・取消")
class PaidLeaveRequestServiceTest extends IntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 4, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    /** 0 回目の付与日（入社から 6 か月後）。 */
    private static final LocalDate FIRST_GRANT = LocalDate.of(2026, 10, 1);
    /** 1 回目の付与日。到来していない。 */
    private static final LocalDate SECOND_GRANT = LocalDate.of(2027, 10, 1);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(10, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }

    @Autowired
    private PaidLeaveRequestService service;
    @Autowired
    private PaidLeaveGrantRepository grants;
    @Autowired
    private MonthlyAttendanceService monthlyAttendances;
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

    private EmployeeId yamadaId;
    private Requester yamada;
    private Requester manager;

    @BeforeEach
    void setUpOrganization() {
        yamadaId = hire("E0001", "山田 太郎", Role.EMPLOYEE);
        EmployeeId managerId = hire("E0100", "課長 次郎", Role.EMPLOYEE);
        hire("E0900", "人事 花子", Role.EMPLOYEE, Role.HR);
        yamada = new Requester(yamadaId, Set.of(Role.EMPLOYEE));
        manager = new Requester(managerId, Set.of(Role.EMPLOYEE, Role.APPROVER));

        var sales = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(sales, new DepartmentCode("SALES"), "営業部"));
        assignments.save(Assignment.startingAt(yamadaId, sales, HIRED));
        assignments.save(Assignment.startingAt(managerId, sales, HIRED));
        managerships.save(Managership.startingAt(sales, managerId, HIRED));

        var standard = new WorkRuleSeriesId(UUID.randomUUID());
        series.save(WorkRuleSeries.active(standard, "標準勤務"));
        workRules.save(WorkRules.versionOf(standard, HIRED, WorkRules.fixed(),
                Duration.ofHours(8), NightWindow.STANDARD));
        series.assign(yamadaId, standard, HIRED);

        // 10 月の土日を休日にする。平日は未登録のまま所定労働日（本番と同じ既定）
        for (LocalDate date = OCTOBER.atDay(1);
                date.isBefore(OCTOBER.plusMonths(1).atDay(1)); date = date.plusDays(1)) {
            switch (date.getDayOfWeek()) {
                case SUNDAY -> calendarRepository.save(date, DayType.LEGAL_HOLIDAY, "法定休日");
                case SATURDAY -> calendarRepository.save(date, DayType.NON_LEGAL_HOLIDAY,
                        "所定休日");
                default -> { }
            }
        }
    }

    @Nested
    @DisplayName("申請")
    class Submission {

        /**
         * 残日数を超えて申請できない（BR-16）。
         *
         * <p><strong>未処理の申請も差し引く。</strong>
         * 承認済みだけを引くと、残 1 日に対して 2 件の申請が同時に通り、
         * 承認の段になって片方が必ず失敗する。
         */
        @Test
        @DisplayName("UT-LV-27 残日数を超える申請は受け付けない")
        void beyondRemainingDays() {
            grantTenDays();
            List<LocalDate> taken = submitPending(10);

            LocalDate eleventh = nextWorkdayAfter(taken.getLast());
            assertThatThrownBy(() -> service.submit(yamada, yamadaId, eleventh,
                    Optional.empty()))
                    .isInstanceOf(InsufficientPaidLeaveException.class);
        }

        /** 同じ 1 日に 2 件の未処理申請を作らない（部分一意インデックスと同じ規則）。 */
        @Test
        @DisplayName("UT-LV-28 同じ 1 日に 2 件目の未処理申請は出せない")
        void duplicateOnSameDate() {
            grantTenDays();
            LocalDate date = LocalDate.of(2026, 11, 16);
            service.submit(yamada, yamadaId, date, Optional.empty());

            assertThatThrownBy(() -> service.submit(yamada, yamadaId, date,
                    Optional.empty()))
                    .isInstanceOf(DuplicateLeaveRequestException.class);
        }

        /** 取下げれば同じ日に出し直せる。塞ぐのは<strong>未処理</strong>の申請だけである。 */
        @Test
        @DisplayName("UT-LV-29 取り下げた日には再び申請できる")
        void resubmitAfterCancel() {
            grantTenDays();
            LocalDate date = LocalDate.of(2026, 11, 16);
            PaidLeaveRequest first = service.submit(yamada, yamadaId, date, Optional.empty());
            service.cancel(yamada, first.id(), first.version());

            PaidLeaveRequest second = service.submit(yamada, yamadaId, date,
                    Optional.empty());

            assertThat(second.status()).isEqualTo(LeaveRequestStatus.SUBMITTED);
            assertThat(second.id()).isNotEqualTo(first.id());
        }

        /**
         * 未到来の付与日を仮に組み入れて申請できる（BR-16）。
         *
         * <p>付与の行は到来したぶんしか作られない。組み入れないと、
         * <strong>次の付与日の直後の日程を、付与日が来るまで誰も申請できない。</strong>
         * 時季指定は労働者の権利（39 条 5 項）であり、システムの都合で妨げてはならない。
         *
         * <p>現在の付与を 10 件の未処理で使い切ったうえで、
         * <strong>次の付与日より後</strong>の取得日を申請する。
         * 使い切っておかないと現在の付与で足りてしまい、この検査は働かない。
         */
        @Test
        @DisplayName("UT-LV-58 未到来の付与日を仮に組み入れて申請できる")
        void scheduledGrantIsCountedIn() {
            grantTenDays();
            submitPending(10);

            LocalDate afterNextGrant = SECOND_GRANT.plusDays(14);
            PaidLeaveRequest request = service.submit(yamada, yamadaId, afterNextGrant,
                    Optional.empty());

            assertThat(request.leaveDate()).isEqualTo(afterNextGrant);
            assertThat(request.status()).isEqualTo(LeaveRequestStatus.SUBMITTED);
        }
    }

    @Nested
    @DisplayName("承認")
    class Approval {

        /**
         * 付与が実体化していない日は承認できない（BR-15）。
         *
         * <p>申請は仮の付与で受け付けるが、<strong>配分できるのは行のある付与だけ</strong>である。
         * 残日数が尽きた場合と案内が違うので、エラーを分ける。
         */
        @Test
        @DisplayName("UT-LV-59 付与が実体化していない日は承認できない")
        void grantNotYetIssued() {
            // 付与の行を作らない。0 回目の付与日は到来しているが、バッチが動いていない状態
            PaidLeaveRequest request = service.submit(yamada, yamadaId,
                    LocalDate.of(2026, 11, 16), Optional.empty());

            assertThatThrownBy(() -> service.approve(manager, request.id(),
                    request.version()))
                    .isInstanceOf(GrantNotYetIssuedException.class);
        }

        /**
         * すでに実労働がある日の年休は承認しない（BR-16）。
         *
         * <p>申請中の取下げに期限を設けない結果、未決裁のまま取得日を過ぎた申請が正当に残る。
         * そのまま承認できると、その日は所定総から除かれるのに実労働もあるので、
         * <strong>不足時間が過少に出る一方で社員は年休を 1 日失う</strong>（落とし穴 97）。
         */
        @Test
        @DisplayName("UT-LV-66 すでに実労働がある日の年休は承認できない")
        void alreadyWorked() {
            grantTenDays();
            LocalDate date = LocalDate.of(2026, 10, 5);
            PaidLeaveRequest request = service.submit(yamada, yamadaId, date,
                    Optional.empty());
            workedOn(date);

            assertThatThrownBy(() -> service.approve(manager, request.id(),
                    request.version()))
                    .isInstanceOf(LeaveDateAlreadyWorkedException.class);
        }
    }

    @Nested
    @DisplayName("取消")
    class Cancellation {

        /**
         * 申請中の取下げは対象月を動かさない（落とし穴 95）。
         *
         * <p>承認していない申請は所定総を変えていないので、戻す理由が無い。
         * 対称に副作用を起こすと、取り下げただけで提出済みの月が下書きへ戻り、
         * <strong>{@code REVERT_BY_LEAVE} という嘘の証跡</strong>が残る。
         */
        @Test
        @DisplayName("UT-LV-64 申請中の取下げは提出済みの月を下書きへ戻さない")
        void cancelPendingKeepsMonth() {
            grantTenDays();
            PaidLeaveRequest request = service.submit(yamada, yamadaId,
                    LocalDate.of(2026, 10, 5), Optional.empty());
            monthlyAttendances.submit(yamada, yamadaId, OCTOBER, Optional.empty(), 0L);
            assertThat(monthlyAttendances.stateOf(yamadaId, OCTOBER))
                    .isEqualTo(AttendanceState.SUBMITTED);

            service.cancel(yamada, request.id(), request.version());

            assertThat(monthlyAttendances.stateOf(yamadaId, OCTOBER))
                    .isEqualTo(AttendanceState.SUBMITTED);
            assertThat(approvalEventKinds()).doesNotContain("REVERT_BY_LEAVE");
        }

        /**
         * 承認済みの月では、承認済みの年休を取り消せない（BR-16）。
         *
         * <p>締め済み（{@code month-already-closed}）と<strong>別のエラーにする。</strong>
         * 承認済みは承認を取り消せば直せるので、利用者への案内がまったく違う。
         */
        @Test
        @DisplayName("UT-LV-65 承認済みの月では承認済みの年休を取り消せない")
        void cancelApprovedInApprovedMonth() {
            grantTenDays();
            PaidLeaveRequest request = service.submit(yamada, yamadaId,
                    LocalDate.of(2026, 10, 5), Optional.empty());
            PaidLeaveRequest approved = service.approve(manager, request.id(),
                    request.version());
            monthlyAttendances.submit(yamada, yamadaId, OCTOBER, Optional.empty(), 0L);
            monthlyAttendances.approve(manager, yamadaId, OCTOBER,
                    monthlyAttendances.currentVersion(manager, yamadaId, OCTOBER));

            assertThatThrownBy(() -> service.cancel(yamada, approved.id(),
                    approved.version()))
                    .isInstanceOf(MonthNotEditableException.class);
        }
    }

    /** 0 回目の付与（10 日）を実体化する。 */
    private void grantTenDays() {
        grants.save(new PaidLeaveGrant(PaidLeaveGrantId.generate(), yamadaId, 0,
                FIRST_GRANT, AttendanceRate.of(120, 120), new GrantDecision.Granted(10),
                FIRST_GRANT.atStartOfDay(), 1L));
    }

    /** 11 月の所定労働日に {@code count} 件の未処理申請を出し、その取得日を返す。 */
    private List<LocalDate> submitPending(int count) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 11, 16);
        for (int i = 0; i < count; i++) {
            service.submit(yamada, yamadaId, date, Optional.empty());
            dates.add(date);
            date = nextWorkdayAfter(date);
        }
        return List.copyOf(dates);
    }

    private LocalDate nextWorkdayAfter(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (calendar.dayTypeOf(next) != DayType.WORKDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    /** その日に 8 時間働いた事実を残す。本番の計算を通して作る（落とし穴 37）。 */
    private void workedOn(LocalDate date) {
        WorkRule rule = workRules.findEffective(yamadaId, date).orElseThrow();
        dailyAttendances.save(yamadaId,
                new DailyAttendances(calendar).fixedDay(date, Duration.ofHours(8)),
                rule.id());
    }

    private List<String> approvalEventKinds() {
        return jdbc.queryForList("SELECT event_kind FROM approval_events", String.class);
    }

    private EmployeeId hire(String number, String name, Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED,
                Optional.empty(), Set.of(roles)));
        return id;
    }
}
