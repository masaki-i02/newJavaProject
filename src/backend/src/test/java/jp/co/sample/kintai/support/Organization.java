package jp.co.sample.kintai.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import jp.co.sample.kintai.employee.domain.OrganizationChart;
import jp.co.sample.kintai.employee.domain.RepositoryBackedOrganizationChart;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 組織をメモリ上に組み立てる。
 *
 * <p><strong>代役は事実だけを答える。</strong>
 * 「所属の履歴から基準日を決める」「祖先を辿る」といった導出は
 * {@code RepositoryBackedOrganizationChart}（本番のコード）に任せる。
 * ここに置くと、そのテストは本番のコードを 1 行も検査しないものになる
 * （CLAUDE.md 落とし穴 37）。
 *
 * <p>唯一の例外が {@code findSelfAndAncestors} である。本番では再帰 CTE が担うので、
 * ここでは親を辿るだけの素朴な実装を置く。<strong>この経路は結合テストで確かめる。</strong>
 */
public final class Organization {

    private final Map<EmployeeId, Employee> employees = new LinkedHashMap<>();
    private final Map<DepartmentId, Department> departments = new LinkedHashMap<>();
    private final List<Assignment> assignments = new ArrayList<>();
    private final List<Managership> managerships = new ArrayList<>();

    public static Organization empty() {
        return new Organization();
    }

    public EmployeeId hire(String number, LocalDate hiredOn, Role... roles) {
        return hire(number, hiredOn, Optional.empty(), roles);
    }

    public EmployeeId hire(String number, LocalDate hiredOn, Optional<LocalDate> retiredOn,
                           Role... roles) {
        Set<Role> granted = roles.length == 0
                ? Set.of(Role.EMPLOYEE)
                : Set.of(roles);
        var id = new EmployeeId(UUID.randomUUID());
        employees.put(id, new Employee(id, new EmployeeNumber(number), number + " 太郎",
                new Email(number.toLowerCase() + "@example.com"), hiredOn, retiredOn, granted));
        return id;
    }

    public DepartmentId department(String code, String name) {
        return department(code, name, Optional.empty(), Optional.empty());
    }

    public DepartmentId department(String code, String name, DepartmentId parent) {
        return department(code, name, Optional.of(parent), Optional.empty());
    }

    public DepartmentId department(String code, String name, Optional<DepartmentId> parent,
                                   Optional<LocalDate> abolishedOn) {
        var id = new DepartmentId(UUID.randomUUID());
        departments.put(id, new Department(id, new DepartmentCode(code), name, parent,
                abolishedOn));
        return id;
    }

    public Organization assign(EmployeeId employee, DepartmentId department, LocalDate from) {
        assignments.add(Assignment.startingAt(employee, department, from));
        return this;
    }

    public Organization assign(EmployeeId employee, DepartmentId department,
                               LocalDate from, LocalDate toExclusive) {
        assignments.add(Assignment.startingAt(employee, department, from)
                .closedAt(toExclusive));
        return this;
    }

    public Organization appoint(DepartmentId department, EmployeeId manager, LocalDate from) {
        managerships.add(Managership.startingAt(department, manager, from));
        return this;
    }

    public Organization appoint(DepartmentId department, EmployeeId manager,
                                LocalDate from, LocalDate toExclusive) {
        managerships.add(Managership.startingAt(department, manager, from)
                .closedAt(toExclusive));
        return this;
    }

    /** 本番の導出ロジックを使った組織図。 */
    public OrganizationChart chart() {
        return new RepositoryBackedOrganizationChart(
                employeeRepository(), departmentRepository(),
                assignmentRepository(), managershipRepository());
    }

    public EmployeeRepository employeeRepository() {
        return new EmployeeRepository() {
            @Override
            public Optional<Employee> findById(EmployeeId id) {
                return Optional.ofNullable(employees.get(id));
            }

            @Override
            public Optional<Employee> findByNumber(EmployeeNumber number) {
                return employees.values().stream()
                        .filter(e -> e.number().equals(number)).findFirst();
            }

            @Override
            public void save(Employee employee, long expectedVersion) {
                // 代役は版を持たない。突き合わせが要るテストは本番のアダプタで書く
                throw new UnsupportedOperationException("版の突き合わせは代役では扱わない");
            }

            @Override
            public long currentVersion(EmployeeId id) {
                throw new UnsupportedOperationException("版の突き合わせは代役では扱わない");
            }

            @Override
            public boolean existsActiveNumber(EmployeeNumber number) {
                return employees.values().stream()
                        .anyMatch(e -> e.number().equals(number) && e.retiredOn().isEmpty());
            }

            @Override
            public boolean existsActiveEmail(Email email) {
                return employees.values().stream()
                        .anyMatch(e -> e.retiredOn().isEmpty()
                                && e.email().value().equalsIgnoreCase(email.value()));
            }

            @Override
            public List<Employee> findForDirectory(LocalDate asOf, boolean includeRetired) {
                return employees.values().stream()
                        .filter(e -> includeRetired || e.retiredOn()
                                .map(retired -> !retired.isBefore(asOf)).orElse(true))
                        .sorted(java.util.Comparator.comparing(e -> e.number().value()))
                        .toList();
            }

            @Override
            public List<Employee> findAll(LocalDate asOf, boolean includeRetired) {
                return employees.values().stream()
                        .filter(e -> includeRetired || e.isActiveOn(asOf))
                        .toList();
            }

            @Override
            public void save(Employee employee) {
                employees.put(employee.id(), employee);
            }
        };
    }

    public DepartmentRepository departmentRepository() {
        return new DepartmentRepository() {
            @Override
            public Optional<Department> findById(DepartmentId id) {
                return Optional.ofNullable(departments.get(id));
            }

            @Override
            public List<Department> findAll() {
                return List.copyOf(departments.values());
            }

            @Override
            public List<Department> findSelfAndAncestors(DepartmentId departmentId) {
                List<Department> path = new ArrayList<>();
                Optional<DepartmentId> current = Optional.of(departmentId);
                while (current.isPresent()) {
                    Department department = departments.get(current.get());
                    if (department == null) {
                        break;
                    }
                    path.add(department);
                    current = department.parentId();
                }
                return List.copyOf(path);
            }

            @Override
            public List<Department> findSelfAndDescendants(DepartmentId departmentId) {
                return departments.values().stream()
                        .filter(d -> findSelfAndAncestors(d.id()).stream()
                                .anyMatch(a -> a.id().equals(departmentId)))
                        .toList();
            }

            @Override
            public void save(Department department) {
                departments.put(department.id(), department);
            }
        };
    }

    public AssignmentRepository assignmentRepository() {
        return new AssignmentRepository() {
            @Override
            public Optional<Assignment> findEffective(EmployeeId employeeId, LocalDate date) {
                return assignments.stream()
                        .filter(a -> a.employeeId().equals(employeeId))
                        .filter(a -> a.covers(date))
                        .findFirst();
            }

            @Override
            public List<Assignment> findHistory(EmployeeId employeeId) {
                return assignments.stream()
                        .filter(a -> a.employeeId().equals(employeeId))
                        .sorted((x, y) -> x.period().from().compareTo(y.period().from()))
                        .toList();
            }

            @Override
            public void save(Assignment assignment) {
                assignments.add(assignment);
            }

            @Override
            public void close(EmployeeId employeeId, LocalDate toExclusive) {
                for (int i = 0; i < assignments.size(); i++) {
                    Assignment a = assignments.get(i);
                    if (a.employeeId().equals(employeeId) && a.period().isUnbounded()) {
                        assignments.set(i, a.closedAt(toExclusive));
                    }
                }
            }
        };
    }

    public ManagershipRepository managershipRepository() {
        return new ManagershipRepository() {
            @Override
            public Optional<Managership> findEffective(DepartmentId departmentId,
                                                       LocalDate date) {
                return managerships.stream()
                        .filter(m -> m.departmentId().equals(departmentId))
                        .filter(m -> m.covers(date))
                        .findFirst();
            }

            @Override
            public List<Managership> findByManager(EmployeeId employeeId, LocalDate date) {
                return managerships.stream()
                        .filter(m -> m.employeeId().equals(employeeId))
                        .filter(m -> m.covers(date))
                        .toList();
            }

            @Override
            public void save(Managership managership) {
                managerships.add(managership);
            }

            @Override
            public void close(DepartmentId departmentId, LocalDate toExclusive) {
                for (int i = 0; i < managerships.size(); i++) {
                    Managership m = managerships.get(i);
                    if (m.departmentId().equals(departmentId) && m.period().isUnbounded()) {
                        managerships.set(i, m.closedAt(toExclusive));
                    }
                }
            }
        };
    }
}
