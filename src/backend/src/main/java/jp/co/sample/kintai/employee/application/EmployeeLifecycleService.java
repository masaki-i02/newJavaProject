package jp.co.sample.kintai.employee.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.employee.domain.Assignment;
import jp.co.sample.kintai.employee.domain.AssignmentRepository;
import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.MonthClosureQuery;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 異動・退職・ロールの変更（{@code ADMIN}）。
 *
 * <p><strong>いずれも承認者を変える力を持つ操作である。</strong>
 * 所属が変われば承認者が変わり、退職すれば部署長が空く。
 * 確定済みの勤怠の承認者が後から変わらないよう、
 * <strong>締め済みの月へ遡る変更は拒む。</strong>
 */
@Service
public class EmployeeLifecycleService {

    private final EmployeeRepository employees;
    private final AssignmentRepository assignments;
    private final ManagershipRepository managerships;
    private final DepartmentRepository departments;
    private final EmployeeVisibility visibility;
    private final MonthClosureQuery monthClosure;
    private final Clock clock;

    public EmployeeLifecycleService(EmployeeRepository employees,
                                    AssignmentRepository assignments,
                                    ManagershipRepository managerships,
                                    DepartmentRepository departments,
                                    EmployeeVisibility visibility,
                                    MonthClosureQuery monthClosure,
                                    Clock clock) {
        this.employees = employees;
        this.assignments = assignments;
        this.managerships = managerships;
        this.departments = departments;
        this.visibility = visibility;
        this.monthClosure = monthClosure;
        this.clock = clock;
    }

    /** 所属履歴。<strong>見てよい社員のぶんだけ。</strong> */
    @Transactional(readOnly = true)
    public List<AssignmentWithDepartment> history(Requester requester, EmployeeId id) {
        Employee employee = load(id);
        if (!visibility.canView(requester, id, LocalDate.now(clock))) {
            throw new AccessDeniedException();
        }
        // 新しい順。valid_to が null は現在の所属
        return assignments.findHistory(employee.id()).stream()
                .sorted(java.util.Comparator.comparing(
                        (Assignment a) -> a.period().from()).reversed())
                .map(a -> new AssignmentWithDepartment(a,
                        departments.findById(a.departmentId()).orElseThrow()))
                .toList();
    }

    /**
     * 異動させる（{@code ADMIN}）。
     *
     * <p>現在の所属を {@code validFrom} で閉じ、新しい所属を開く。1 トランザクション。
     *
     * <p><strong>遡及異動は拒む。</strong>
     * 既に締めた月の承認者が変わってしまう。
     * 実務では発令漏れの訂正が要るが、締めを戻す手段と併せて決める必要がある。
     */
    @Transactional
    public void transfer(Requester requester, EmployeeId id, DepartmentId departmentId,
                         LocalDate validFrom) {
        requireAdmin(requester);
        Employee employee = load(id);
        if (validFrom.isBefore(employee.hiredOn())) {
            throw new BeforeHireDateException(validFrom, employee.hiredOn());
        }
        Department department = departments.findById(departmentId)
                .orElseThrow(() -> new EmployeeDirectoryService
                        .DepartmentNotFoundException(departmentId));
        if (!department.isActiveOn(validFrom)) {
            throw new EmployeeDirectoryService.DepartmentAbolishedException(department);
        }
        requireMonthNotClosed(id, YearMonth.from(validFrom), "異動");

        assignments.close(id, validFrom);
        assignments.save(Assignment.startingAt(id, departmentId, validFrom));
    }

    /**
     * 退職を登録する（{@code ADMIN}）。<strong>副作用を伴う。</strong>
     *
     * <ol>
     *   <li>{@code retired_on} を設定する</li>
     *   <li>開いている所属を<strong>退職日の翌日</strong>で閉じる</li>
     *   <li>開いている部署長を<strong>退職日の翌日</strong>で閉じる</li>
     * </ol>
     *
     * <p><strong>翌日で閉じるのは、退職日当日は在籍しているからである。</strong>
     * 当日で閉じると、最終日の勤怠の承認者が導出できなくなる。
     *
     * <p>3 を忘れると、<strong>その部署に所属する全社員の承認者が
     * 退職者になり続ける。</strong>
     */
    @Transactional
    public RetirementResult retire(Requester requester, EmployeeId id, LocalDate retiredOn,
                                   long expectedVersion) {
        requireAdmin(requester);
        Employee employee = load(id);
        if (employee.retiredOn().isPresent()) {
            throw new AlreadyRetiredException(id, employee.retiredOn().get());
        }
        requireMonthNotClosed(id, YearMonth.from(retiredOn), "退職の登録");

        // 退職日が入社日より前かどうかは Employee の不変条件が拒む
        var retired = new Employee(employee.id(), employee.number(), employee.name(),
                employee.email(), employee.hiredOn(), Optional.of(retiredOn),
                employee.roles());
        employees.save(retired, expectedVersion);

        LocalDate dayAfter = retiredOn.plusDays(1);
        assignments.close(id, dayAfter);
        int closedManagerships = managerships.closeByManager(id, dayAfter);
        return new RetirementResult(retiredOn, 1, closedManagerships);
    }

    /**
     * 退職を取り消す（{@code ADMIN}）。<strong>誤登録の訂正にだけ使う。</strong>
     *
     * <p>閉じた所属・部署長を開き直す。閉じた日を指定して戻すので、
     * 退職とは無関係に閉じた過去の期間（異動・交代）は巻き戻さない。
     */
    @Transactional
    public RetirementResult cancelRetirement(Requester requester, EmployeeId id,
                                             long expectedVersion) {
        requireAdmin(requester);
        Employee employee = load(id);
        LocalDate retiredOn = employee.retiredOn()
                .orElseThrow(() -> new NotRetiredException(id));

        var active = new Employee(employee.id(), employee.number(), employee.name(),
                employee.email(), employee.hiredOn(), Optional.empty(), employee.roles());
        employees.save(active, expectedVersion);

        LocalDate dayAfter = retiredOn.plusDays(1);
        int reopenedAssignments = assignments.reopenClosedAt(id, dayAfter);
        int reopenedManagerships = managerships.reopenClosedAt(id, dayAfter);
        return new RetirementResult(retiredOn, reopenedAssignments, reopenedManagerships);
    }

    /**
     * ロールを付与・剥奪する（{@code ADMIN}）。
     *
     * <p><strong>{@code EMPLOYEE} は外せない</strong>（要件定義書 4 章）。
     * 自分の打刻ができない社員は存在しない。
     *
     * <p><strong>{@code APPROVER} は受け付けない。</strong>
     * 「承認者かどうか」の実体は部署長を務めているかであり、認証時に導出する。
     * ロールとして持たせると「部署長だがロールが無く 403」
     * 「ロールはあるが対象 0 件」という不整合が起きる。
     */
    @Transactional
    public Employee changeRoles(Requester requester, EmployeeId id, Set<Role> roles,
                                long expectedVersion) {
        requireAdmin(requester);
        if (roles.contains(Role.APPROVER)) {
            throw new NotAssignableRoleException(Role.APPROVER);
        }
        Employee employee = load(id);

        var next = java.util.EnumSet.of(Role.EMPLOYEE);
        next.addAll(roles);
        var updated = new Employee(employee.id(), employee.number(), employee.name(),
                employee.email(), employee.hiredOn(), employee.retiredOn(), next);
        employees.save(updated, expectedVersion);
        return updated;
    }

    private Employee load(EmployeeId id) {
        return employees.findById(id)
                .orElseThrow(() -> new EmployeeDirectoryService
                        .EmployeeNotFoundException(id));
    }

    /**
     * 締め済みの月へ遡っていないか。
     *
     * <p>所属と部署長が変われば承認者が変わる。
     * <strong>確定済みの勤怠の承認者が後から変わってはいけない。</strong>
     */
    private void requireMonthNotClosed(EmployeeId id, YearMonth month, String 操作) {
        if (monthClosure.isClosed(id, month)) {
            throw new MonthAlreadyClosedException(month, 操作);
        }
    }

    private static void requireAdmin(Requester requester) {
        if (!requester.has(Role.ADMIN)) {
            throw new AccessDeniedException();
        }
    }

    /** 所属と、その部署。 */
    public record AssignmentWithDepartment(Assignment assignment, Department department) {
    }

    /**
     * 退職の登録・取消の結果。
     *
     * <p><strong>閉じた（開き直した）件数を返す。</strong>
     * 部署長を務めていたかどうかは管理者に見せる必要がある。
     */
    public record RetirementResult(LocalDate retiredOn, int assignments,
                                   int managerships) {
    }

    /** すでに退職している。 */
    public static final class AlreadyRetiredException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        AlreadyRetiredException(EmployeeId id, LocalDate retiredOn) {
            super("すでに退職しています: 社員 %s / 退職日 %s".formatted(id.value(), retiredOn));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:already-retired";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "すでに退職しています";
        }
    }

    /** 退職していないのに取り消そうとした。 */
    public static final class NotRetiredException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        NotRetiredException(EmployeeId id) {
            super("退職していません: " + id.value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:not-retired";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "退職していません";
        }
    }

    /** 入社日より前に所属を作ろうとした。 */
    public static final class BeforeHireDateException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        BeforeHireDateException(LocalDate validFrom, LocalDate hiredOn) {
            super("入社日より前には所属できません: 開始 %s / 入社 %s"
                    .formatted(validFrom, hiredOn));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:before-hire-date";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "入社日より前には所属できません";
        }
    }

    /** 締め済みの月へ遡る変更。<strong>確定済みの勤怠の承認者が変わってしまう。</strong> */
    public static final class MonthAlreadyClosedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        MonthAlreadyClosedException(YearMonth month, String 操作) {
            super("締め済みの月に遡るため%sできません: %s".formatted(操作, month));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:month-already-closed";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "締め済みの月に遡る変更はできません";
        }
    }

    /** 付与できないロール。 */
    public static final class NotAssignableRoleException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        NotAssignableRoleException(Role role) {
            super("%s は付与できません。部署長を務めている事実から導出されます"
                    .formatted(role));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:not-assignable-role";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "そのロールは付与できません";
        }
    }
}
