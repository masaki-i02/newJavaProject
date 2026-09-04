package jp.co.sample.kintai.employee.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.employee.domain.Assignment;
import jp.co.sample.kintai.employee.domain.AssignmentRepository;
import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.OrganizationChart;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 社員名簿の参照と登録（要件定義書 4.1）。
 *
 * <p><strong>閲覧範囲は Spring Security のロール判定では表せない。</strong>
 * 「自分が長を務める部署の配下か」は組織と基準日に依存する業務判断なので、
 * ここが {@link EmployeeVisibility} を通じて判定する。
 *
 * <p>登録・更新は {@code ADMIN} に限る。人事情報の書き換えは
 * 勤怠の承認者を変える力を持つため、閲覧より狭くする。
 */
@Service
public class EmployeeDirectoryService {

    private final EmployeeRepository employees;
    private final AssignmentRepository assignments;
    private final DepartmentRepository departments;
    private final OrganizationChart chart;
    private final EmployeeVisibility visibility;
    private final Clock clock;

    public EmployeeDirectoryService(EmployeeRepository employees,
                                    AssignmentRepository assignments,
                                    DepartmentRepository departments,
                                    OrganizationChart chart,
                                    EmployeeVisibility visibility,
                                    Clock clock) {
        this.employees = employees;
        this.assignments = assignments;
        this.departments = departments;
        this.chart = chart;
        this.visibility = visibility;
        this.clock = clock;
    }

    /**
     * 社員一覧。<strong>見てよい社員のぶんだけ返す。</strong>
     *
     * <p><strong>所属を持たない社員も返す。</strong>
     * 未来日入社の社員が登録直後の一覧に現れないと、
     * 管理者が登録の成否を確認できない。
     *
     * @param date         この日付時点の所属を付ける。空なら当日
     * @param departmentId 指定があれば、その部署の配下に絞る
     */
    @Transactional(readOnly = true)
    public List<EmployeeWithDepartment> list(Requester requester, Optional<LocalDate> date,
                                             Optional<DepartmentId> departmentId,
                                             boolean includeRetired) {
        LocalDate asOf = date.orElseGet(() -> LocalDate.now(clock));
        Set<DepartmentId> scope = departmentId.map(this::selfAndDescendantIds)
                .orElse(Set.of());

        return employees.findForDirectory(asOf, includeRetired).stream()
                .filter(employee -> visibility.canView(requester, employee.id(), asOf))
                .map(employee -> new EmployeeWithDepartment(employee,
                        chart.departmentOf(employee.id(), asOf)))
                .filter(row -> departmentId.isEmpty()
                        || row.department().map(d -> scope.contains(d.id())).orElse(false))
                .toList();
    }

    /** 社員 1 人。<strong>見てよいかを確かめてから返す。</strong> */
    @Transactional(readOnly = true)
    public EmployeeWithDepartment find(Requester requester, EmployeeId id,
                                       Optional<LocalDate> date) {
        LocalDate asOf = date.orElseGet(() -> LocalDate.now(clock));
        Employee employee = load(id);
        if (!visibility.canView(requester, id, asOf)) {
            throw new AccessDeniedException();
        }
        return new EmployeeWithDepartment(employee, chart.departmentOf(id, asOf));
    }

    /**
     * 社員を登録する（{@code ADMIN}）。
     *
     * <p><strong>登録と同時に所属を作る。</strong>
     * 所属が無いと承認者が決まらず、勤怠を提出できない。
     *
     * <p>{@code EMPLOYEE} はサーバが無条件に付与する（要件定義書 4 章）。
     * 呼び出し側が指定できるのは追加のロールだけである。
     */
    @Transactional
    public EmployeeWithDepartment register(Requester requester, EmployeeNumber number,
                                           String name, Email email, LocalDate hiredOn,
                                           DepartmentId departmentId,
                                           Set<Role> additionalRoles) {
        requireAdmin(requester);
        if (employees.existsActiveNumber(number)) {
            throw new DuplicateEmployeeNumberException(number);
        }
        if (employees.existsActiveEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        Department department = loadDepartment(departmentId);
        if (!department.isActiveOn(hiredOn)) {
            throw new DepartmentAbolishedException(department);
        }

        var roles = java.util.EnumSet.of(Role.EMPLOYEE);
        roles.addAll(additionalRoles);
        var employee = new Employee(new EmployeeId(UUID.randomUUID()), number, name,
                email, hiredOn, Optional.empty(), roles);

        employees.save(employee);
        // 所属の開始日は入社日。入社前に所属が始まる状態を作らない
        assignments.save(Assignment.startingAt(employee.id(), departmentId, hiredOn));
        return new EmployeeWithDepartment(employee, Optional.of(department));
    }

    /**
     * 氏名とメールアドレスを更新する（{@code ADMIN}）。
     *
     * <p><strong>更新できる項目を絞る。</strong>
     * 社員番号は認証 ID を兼ね、入社日は所属の開始日との整合が崩れ、
     * 退職日とロールは副作用があるので、それぞれ別の経路で扱う。
     */
    @Transactional
    public EmployeeWithDepartment update(Requester requester, EmployeeId id, String name,
                                         Email email, long expectedVersion) {
        requireAdmin(requester);
        Employee current = load(id);
        if (!current.email().equals(email) && employees.existsActiveEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        var updated = new Employee(current.id(), current.number(), name, email,
                current.hiredOn(), current.retiredOn(), current.roles());
        employees.save(updated, expectedVersion);
        return new EmployeeWithDepartment(updated,
                chart.departmentOf(id, LocalDate.now(clock)));
    }

    /** 現在の版（API設計書 1.4）。取得する経路が無いと利用者は版を送れない。 */
    @Transactional(readOnly = true)
    public long currentVersion(Requester requester, EmployeeId id) {
        find(requester, id, Optional.empty());
        return employees.currentVersion(id);
    }

    private Set<DepartmentId> selfAndDescendantIds(DepartmentId departmentId) {
        return departments.findSelfAndDescendants(departmentId).stream()
                .map(Department::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Employee load(EmployeeId id) {
        return employees.findById(id).orElseThrow(() -> new EmployeeNotFoundException(id));
    }

    private Department loadDepartment(DepartmentId id) {
        return departments.findById(id)
                .orElseThrow(() -> new DepartmentNotFoundException(id));
    }

    private static void requireAdmin(Requester requester) {
        if (!requester.has(Role.ADMIN)) {
            throw new AccessDeniedException();
        }
    }

    /** 社員と、基準日時点の所属。<strong>所属は無いことがある</strong>（未来日入社）。 */
    public record EmployeeWithDepartment(Employee employee, Optional<Department> department) {

        public EmployeeWithDepartment {
            if (employee == null || department == null) {
                throw new IllegalArgumentException("社員と所属に null は許されません");
            }
        }
    }

    /** 社員が存在しない。 */
    public static final class EmployeeNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        EmployeeNotFoundException(EmployeeId id) {
            super("社員が見つかりません: " + id.value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:resource-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "社員が見つかりません";
        }
    }

    /** 部署が存在しない。 */
    public static final class DepartmentNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        DepartmentNotFoundException(DepartmentId id) {
            super("部署が見つかりません: " + id.value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:resource-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "部署が見つかりません";
        }
    }

    /**
     * 社員番号が在籍者と重複している。
     *
     * <p>退職者の社員番号は再利用できるので、<strong>在籍者の間でだけ</strong>一意である。
     */
    public static final class DuplicateEmployeeNumberException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        DuplicateEmployeeNumberException(EmployeeNumber number) {
            super("社員番号 %s は既に使用されています".formatted(number.value()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:duplicate-employee-number";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "社員番号が重複しています";
        }
    }

    /** メールアドレスが在籍者と重複している。<strong>大文字小文字は区別しない。</strong> */
    public static final class DuplicateEmailException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        DuplicateEmailException(Email email) {
            super("メールアドレス %s は既に使用されています".formatted(email.value()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:duplicate-email";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "メールアドレスが重複しています";
        }
    }

    /** 廃止済みの部署へ所属させようとした。 */
    public static final class DepartmentAbolishedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        DepartmentAbolishedException(Department department) {
            super("廃止済みの部署には所属できません: " + department.code().value());
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:department-abolished";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "廃止済みの部署には所属できません";
        }
    }
}
