package jp.co.sample.kintai.employee.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentCode;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.Managership;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.shared.application.AccessDeniedException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 部署ツリーの参照と保守。
 *
 * <p><strong>組織図の全体を一般社員へ公開しない</strong>（要件定義書 4.1）。
 * 誰が誰の下にいるかは人事情報であり、勤怠の記録・提出という業務に必要ない。
 * 承認者は自分が長を務める部署以下だけを見る。
 */
@Service
public class DepartmentService {

    private final DepartmentRepository departments;
    private final ManagershipRepository managerships;
    private final EmployeeRepository employees;
    private final Clock clock;

    public DepartmentService(DepartmentRepository departments,
                             ManagershipRepository managerships,
                             EmployeeRepository employees, Clock clock) {
        this.departments = departments;
        this.managerships = managerships;
        this.employees = employees;
        this.clock = clock;
    }

    /**
     * 部署ツリー。
     *
     * <p><strong>全件を 1 度読んでからツリーへ組み立てる。</strong>
     * 階層ごとに問い合わせると N+1 になる。
     *
     * @param includeAbolished 廃止済みの部署を含めるか。既定では含めない
     */
    @Transactional(readOnly = true)
    public List<Node> tree(Requester requester, boolean includeAbolished) {
        LocalDate today = LocalDate.now(clock);
        Set<DepartmentId> scope = visibleScope(requester, today);

        List<Department> all = departments.findAll().stream()
                .filter(d -> includeAbolished || d.isActiveOn(today))
                .filter(d -> scope.isEmpty() || scope.contains(d.id()))
                .toList();
        return assemble(all, scope, today);
    }

    /** 部署を登録する（{@code ADMIN}）。 */
    @Transactional
    public Department create(Requester requester, DepartmentCode code, String name,
                             Optional<DepartmentId> parentId) {
        requireAdmin(requester);
        if (departments.existsActiveCode(code)) {
            throw new DuplicateDepartmentCodeException(code);
        }
        var id = new DepartmentId(UUID.randomUUID());
        Department department = parentId
                .map(parent -> {
                    requireExists(parent);
                    return Department.under(id, code, name, parent);
                })
                .orElseGet(() -> Department.root(id, code, name));
        departments.save(department);
        return department;
    }

    /**
     * 部署を更新する（{@code ADMIN}）。
     *
     * <p><strong>親を変えるときはテーブルロックを取る。</strong>
     * 循環検出トリガは他トランザクションの未コミットの変更を見ないため、
     * 「A の親を C に」「C の親を A に」が同時に走ると
     * <strong>どちらのトリガも循環を見つけられないまま両方がコミットされる。</strong>
     */
    @Transactional
    public Department update(Requester requester, DepartmentId id, DepartmentCode code,
                            String name, Optional<DepartmentId> parentId) {
        requireAdmin(requester);
        Department current = load(id);
        if (!current.code().equals(code) && departments.existsActiveCode(code)) {
            throw new DuplicateDepartmentCodeException(code);
        }
        if (!current.parentId().equals(parentId)) {
            departments.lockForHierarchyChange();
            parentId.ifPresent(parent -> {
                requireExists(parent);
                requireNotOwnDescendant(id, parent);
            });
        }
        var updated = new Department(id, code, name, parentId, current.abolishedOn());
        departments.save(updated);
        return updated;
    }

    /**
     * 部署を廃止する（{@code ADMIN}）。
     *
     * <p><strong>配下に現存する部署があれば拒む。</strong>
     * 親だけを廃止すると、子が親のいない部署として残り、
     * 承認者の遡りが根へ到達できなくなる。
     */
    @Transactional
    public Department abolish(Requester requester, DepartmentId id, LocalDate abolishedOn) {
        requireAdmin(requester);
        Department current = load(id);
        List<Department> livingChildren = departments.findSelfAndDescendants(id).stream()
                .filter(d -> !d.id().equals(id))
                .filter(d -> d.isActiveOn(abolishedOn))
                .toList();
        if (!livingChildren.isEmpty()) {
            throw new HasLivingChildrenException(current, livingChildren);
        }
        Department abolished = current.abolish(abolishedOn);
        departments.save(abolished);
        return abolished;
    }

    /**
     * 部署長を設定・交代させる（{@code ADMIN}）。
     *
     * <p>現任の期間を閉じて新しい期間を開く。期間の重複は DB の排他制約が拒む。
     */
    @Transactional
    public void appointManager(Requester requester, DepartmentId departmentId,
                               EmployeeId employeeId, LocalDate validFrom) {
        requireAdmin(requester);
        Department department = load(departmentId);
        if (!department.isActiveOn(validFrom)) {
            throw new EmployeeDirectoryService.DepartmentAbolishedException(department);
        }
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new EmployeeDirectoryService
                        .EmployeeNotFoundException(employeeId));
        if (!employee.isActiveOn(validFrom)) {
            throw new RetiredEmployeeException(employeeId, validFrom);
        }
        managerships.close(departmentId, validFrom);
        managerships.save(Managership.startingAt(departmentId, employeeId, validFrom));
    }

    /**
     * 見てよい部署。
     *
     * <p>空集合は<strong>「絞らない」</strong>を意味する（人事・管理者）。
     * 承認者は自分が長を務める部署とその配下だけを見る。
     */
    private Set<DepartmentId> visibleScope(Requester requester, LocalDate today) {
        if (requester.canReachEveryone()) {
            return Set.of();
        }
        // ★ ロール（APPROVER）ではなく、その日に長を務めている事実で判定する。
        //   APPROVER は認証時に managerships から導出される値なので、
        //   ロールを先に見ても同じことを二度訊くだけになる（落とし穴 77）。
        //   一般社員はここで 0 件になり、組織図を見られない
        Set<DepartmentId> scope = managerships
                .findByManager(requester.employeeId(), today).stream()
                .flatMap(m -> departments.findSelfAndDescendants(m.departmentId()).stream())
                .map(Department::id)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (scope.isEmpty()) {
            throw new AccessDeniedException();
        }
        return scope;
    }

    /**
     * ツリーへ組み立てる。
     *
     * <p>親が対象に含まれない部署（承認者から見た自部署など）は根として扱う。
     * そうしないと、<strong>見えている部署がどこにも現れなくなる。</strong>
     */
    private List<Node> assemble(List<Department> all, Set<DepartmentId> scope,
                                LocalDate today) {
        Map<DepartmentId, List<Department>> byParent = new LinkedHashMap<>();
        Set<DepartmentId> present = all.stream().map(Department::id)
                .collect(Collectors.toUnmodifiableSet());
        List<Department> roots = new ArrayList<>();
        for (Department d : all) {
            Optional<DepartmentId> parent = d.parentId().filter(present::contains);
            if (parent.isEmpty()) {
                roots.add(d);
            } else {
                byParent.computeIfAbsent(parent.get(), key -> new ArrayList<>()).add(d);
            }
        }
        return roots.stream().map(root -> toNode(root, byParent, today)).toList();
    }

    private Node toNode(Department department,
                        Map<DepartmentId, List<Department>> byParent, LocalDate today) {
        List<Node> children = byParent.getOrDefault(department.id(), List.of()).stream()
                .map(child -> toNode(child, byParent, today)).toList();
        Optional<ManagerView> manager = managerships
                .findEffective(department.id(), today)
                .flatMap(m -> employees.findById(m.employeeId())
                        .map(e -> new ManagerView(e, m.period().from())));
        return new Node(department, manager, children);
    }

    /**
     * 新しい親が自分自身の配下でないか。
     *
     * <p>DB の循環検出トリガも同じことを守っているが、
     * <strong>トリガの {@code RAISE} は制約違反ではないので Problem Details に写らない。</strong>
     * 親の選び間違いは利用者の入力の誤りであり、500 で返してよいものではない。
     *
     * <p>ここで検査できるのは<strong>ロックを取ったあとだから</strong>である。
     * ロックが無ければ、読んだ直後に他のトランザクションが親を変えうる。
     * トリガは最後の防波堤として残す。
     */
    private void requireNotOwnDescendant(DepartmentId id, DepartmentId newParent) {
        boolean withinOwnSubtree = departments.findSelfAndDescendants(id).stream()
                .map(Department::id).anyMatch(newParent::equals);
        if (withinOwnSubtree) {
            throw new CyclicHierarchyException(id, newParent);
        }
    }

    private Department load(DepartmentId id) {
        return departments.findById(id)
                .orElseThrow(() -> new EmployeeDirectoryService
                        .DepartmentNotFoundException(id));
    }

    private void requireExists(DepartmentId id) {
        load(id);
    }

    private static void requireAdmin(Requester requester) {
        if (!requester.has(Role.ADMIN)) {
            throw new AccessDeniedException();
        }
    }

    /** ツリーの節。 */
    public record Node(Department department, Optional<ManagerView> manager,
                       List<Node> children) {
    }

    /** 部署長。<strong>就任日を添える</strong>（「いつからこの人か」は問い合わせが来る）。 */
    public record ManagerView(Employee employee, LocalDate since) {
    }

    /**
     * 親に自分自身か自分の配下を指定した。
     *
     * <p><strong>循環すると承認者の遡りが根へ到達できず、無限に回る。</strong>
     */
    public static final class CyclicHierarchyException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        CyclicHierarchyException(DepartmentId id, DepartmentId newParent) {
            super("自分自身または配下の部署を親にはできません: 部署 %s / 親 %s"
                    .formatted(id.value(), newParent.value()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:cyclic-department-hierarchy";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "自分自身または配下の部署を親にはできません";
        }
    }

    /** 部署コードが現存部署と重複している。 */
    public static final class DuplicateDepartmentCodeException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        DuplicateDepartmentCodeException(DepartmentCode code) {
            super("部署コード %s は既に使用されています".formatted(code.value()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:duplicate-department-code";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "部署コードが重複しています";
        }
    }

    /** 配下に現存する部署が残っている。 */
    public static final class HasLivingChildrenException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        HasLivingChildrenException(Department department, List<Department> children) {
            super("配下に現存する部署があるため廃止できません: %s（配下 %d 件）"
                    .formatted(department.code().value(), children.size()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:department-has-children";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "配下に現存する部署があるため廃止できません";
        }
    }

    /** 退職済みの社員を部署長にしようとした。 */
    public static final class RetiredEmployeeException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        RetiredEmployeeException(EmployeeId id, LocalDate date) {
            super("その日に在籍していない社員は部署長にできません: 社員 %s / %s"
                    .formatted(id.value(), date));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:retired-employee";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.RULE_VIOLATION;
        }

        @Override
        public String title() {
            return "在籍していない社員は部署長にできません";
        }
    }
}
