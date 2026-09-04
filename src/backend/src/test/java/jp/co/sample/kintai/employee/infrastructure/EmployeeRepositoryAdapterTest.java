package jp.co.sample.kintai.employee.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
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
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 社員・組織の永続化アダプタ（IT-EMP-15〜24）。
 *
 * <p><strong>ここで確かめたいのは、ドメインが持つ番兵が DB の {@code NULL} に
 * 正しく写り、読み戻して元の値になることである。</strong>
 * 単体テストは代役を相手にしているので、この写像は一度も通っていない。
 */
@DisplayName("社員・組織の永続化")
class EmployeeRepositoryAdapterTest extends IntegrationTestBase {

    private static final LocalDate APR_1 = LocalDate.of(2026, 4, 1);

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private DepartmentRepository departments;
    @Autowired
    private AssignmentRepository assignments;
    @Autowired
    private ManagershipRepository managerships;
    @Autowired
    private OrganizationChart chart;

    private DepartmentId head;
    private DepartmentId division;
    private DepartmentId section;

    @BeforeEach
    void setUpOrganization() {
        head = new DepartmentId(UUID.randomUUID());
        division = new DepartmentId(UUID.randomUUID());
        section = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(head, new DepartmentCode("H001"), "営業本部"));
        departments.save(Department.under(division, new DepartmentCode("D001"), "第一営業部",
                head));
        departments.save(Department.under(section, new DepartmentCode("S001"), "第一営業課",
                division));
    }

    private EmployeeId hire(String number, LocalDate hiredOn) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), number + " 太郎",
                new Email(number.toLowerCase() + "@example.com"), hiredOn,
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        return id;
    }

    @Nested
    @DisplayName("社員")
    class Employees {

        @Test
        @DisplayName("IT-EMP-15 保存して読み戻すと同じ値になる")
        void roundTrip() {
            var id = new EmployeeId(UUID.randomUUID());
            var saved = new Employee(id, new EmployeeNumber("E0001"), "山田 太郎",
                    new Email("Taro.Yamada@Example.com"), APR_1, Optional.empty(),
                    Set.of(Role.EMPLOYEE, Role.APPROVER));
            employees.save(saved);

            assertThat(employees.findById(id)).contains(saved);
            // メールは小文字に正規化されて保存される（DB の一意制約が lower(email) のため）
            assertThat(employees.findById(id).orElseThrow().email().value())
                    .isEqualTo("taro.yamada@example.com");
        }

        @Test
        @DisplayName("IT-EMP-16 社員番号で引ける（認証 ID を兼ねる）")
        void findByNumber() {
            hire("E0001", APR_1);

            assertThat(employees.findByNumber(new EmployeeNumber("E0001"))).isPresent();
            assertThat(employees.findByNumber(new EmployeeNumber("E9999"))).isEmpty();
        }

        /**
         * 退職日は<strong>最終在籍日</strong>なので、退職日当日は在籍者として引ける。
         * ここを {@code >} で書くと退職日当日の勤怠が誰にも見えなくなる。
         */
        @Test
        @DisplayName("IT-EMP-17 退職日当日は在籍者として引ける。翌日は引けない")
        void retirementBoundary() {
            var id = hire("E0001", APR_1);
            var retiredOn = LocalDate.of(2026, 9, 20);
            employees.save(employees.findById(id).orElseThrow().retire(retiredOn));

            assertThat(employees.findAll(retiredOn, false)).extracting(Employee::id)
                    .as("退職日当日").contains(id);
            assertThat(employees.findAll(retiredOn.plusDays(1), false))
                    .as("退職日翌日").isEmpty();
            assertThat(employees.findAll(retiredOn.plusDays(1), true))
                    .as("退職者を含めれば引ける").hasSize(1);
        }

        @Test
        @DisplayName("IT-EMP-18 ロールは複数保存でき、読み戻せる")
        void roles() {
            var id = new EmployeeId(UUID.randomUUID());
            employees.save(new Employee(id, new EmployeeNumber("E0002"), "人事 花子",
                    new Email("hr@example.com"), APR_1, Optional.empty(),
                    Set.of(Role.EMPLOYEE, Role.HR, Role.ADMIN)));

            assertThat(employees.findById(id).orElseThrow().roles())
                    .containsExactlyInAnyOrder(Role.EMPLOYEE, Role.HR, Role.ADMIN);
        }
    }

    @Nested
    @DisplayName("部署の階層")
    class Hierarchy {

        /**
         * <strong>再帰 CTE の経路はここでしか通らない。</strong>
         * 単体テストの代役は親を 1 段ずつ辿る素朴な実装を使っている。
         */
        @Test
        @DisplayName("IT-EMP-19 祖先を自分自身から根へ向かう順で返す")
        void selfAndAncestors() {
            assertThat(departments.findSelfAndAncestors(section))
                    .extracting(Department::name)
                    .containsExactly("第一営業課", "第一営業部", "営業本部");
        }

        @Test
        @DisplayName("IT-EMP-20 配下を自分自身から順に返す")
        void selfAndDescendants() {
            assertThat(departments.findSelfAndDescendants(head))
                    .extracting(Department::name)
                    .containsExactly("営業本部", "第一営業部", "第一営業課");
        }

        @Test
        @DisplayName("ルート部署の親は空として読み戻される")
        void rootHasNoParent() {
            assertThat(departments.findById(head).orElseThrow().isRoot()).isTrue();
            assertThat(departments.findById(section).orElseThrow().parentId())
                    .contains(division);
        }

        @Test
        @DisplayName("廃止日を保存して読み戻せる")
        void abolished() {
            var abolishedOn = LocalDate.of(2026, 10, 1);
            departments.save(departments.findById(section).orElseThrow().abolish(abolishedOn));

            var reloaded = departments.findById(section).orElseThrow();
            assertThat(reloaded.abolishedOn()).contains(abolishedOn);
            assertThat(reloaded.isActiveOn(abolishedOn.minusDays(1))).isTrue();
            assertThat(reloaded.isActiveOn(abolishedOn))
                    .as("廃止日当日はもう現存しない").isFalse();
        }
    }

    @Nested
    @DisplayName("所属と部署長（番兵と NULL の写像）")
    class Periods {

        /**
         * <strong>この往復がこのテストの主眼である。</strong>
         * ドメインは無期限を {@code LocalDate.MAX} の番兵で表すが、
         * それは PostgreSQL の {@code date} に入らない。
         * アダプタが {@code NULL} へ写し、読み戻しで番兵へ戻すことを確かめる。
         */
        @Test
        @DisplayName("IT-EMP-21 無期限の所属は NULL で保存され、番兵として読み戻される")
        void unboundedRoundTrip() {
            var taro = hire("E0001", APR_1);
            assignments.save(Assignment.startingAt(taro, section, APR_1));

            assertThat(jdbc.queryForObject(
                    "SELECT valid_to IS NULL FROM assignments WHERE employee_id = ?",
                    Boolean.class, taro.value()))
                    .as("DB では NULL").isTrue();
            assertThat(assignments.findEffective(taro, APR_1).orElseThrow().period()
                    .isUnbounded())
                    .as("ドメインでは番兵").isTrue();
            assertThat(assignments.findEffective(taro, LocalDate.of(2099, 1, 1)))
                    .as("無期限なので遠い未来でも有効").isPresent();
        }

        /**
         * 期間は半開区間なので、異動日当日は<strong>新しい部署</strong>に属する。
         * ここを {@code >=} で書くと 2 件返り、DB の {@code assignments_no_overlap} が
         * 保証している一意性をアプリケーション側で壊す。
         */
        @Test
        @DisplayName("IT-EMP-22 異動日当日は新しい所属を返す（半開区間）")
        void transferBoundary() {
            var taro = hire("E0001", APR_1);
            var transferredOn = LocalDate.of(2026, 7, 1);
            assignments.save(Assignment.startingAt(taro, section, APR_1));
            assignments.close(taro, transferredOn);
            assignments.save(Assignment.startingAt(taro, division, transferredOn));

            assertThat(assignments.findEffective(taro, transferredOn.minusDays(1)))
                    .map(Assignment::departmentId).contains(section);
            assertThat(assignments.findEffective(taro, transferredOn))
                    .map(Assignment::departmentId).contains(division);
        }

        @Test
        @DisplayName("IT-EMP-23 部署長の交代を基準日で引き分けられる")
        void managerSuccession() {
            var first = hire("E0002", APR_1);
            var second = hire("E0003", APR_1);
            var changedOn = LocalDate.of(2026, 7, 1);
            managerships.save(Managership.startingAt(division, first, APR_1));
            managerships.close(division, changedOn);
            managerships.save(Managership.startingAt(division, second, changedOn));

            assertThat(managerships.findEffective(division, changedOn.minusDays(1)))
                    .map(Managership::employeeId).contains(first);
            assertThat(managerships.findEffective(division, changedOn))
                    .map(Managership::employeeId).contains(second);
        }

        /** 部署長の兼任は認めるので、1 人が複数部署の長を務められる（BR-11 補足）。 */
        @Test
        @DisplayName("IT-EMP-24 部署長の兼任は認められる")
        void concurrentManagerships() {
            var boss = hire("E0002", APR_1);
            managerships.save(Managership.startingAt(head, boss, APR_1));
            managerships.save(Managership.startingAt(division, boss, APR_1));

            assertThat(managerships.findByManager(boss, APR_1))
                    .extracting(Managership::departmentId)
                    .containsExactlyInAnyOrder(head, division);
        }

        /**
         * 開いた所属を 2 つ作ろうとしても、<strong>DB が作らせない。</strong>
         *
         * <p>アダプタ側にも「複数開いていたら例外」という防波堤を置いているが、
         * それは制約が落ちた場合の二重の守りであり、
         * <strong>この経路では到達できない</strong>（到達できたら制約が壊れている）。
         * 防波堤そのものは {@code AssignmentRepositoryAdapterGuardTest} が直接叩く。
         */
        @Test
        @DisplayName("開いた所属を 2 つ作ることは DB ができなくしている")
        void twoOpenAssignmentsAreImpossible() {
            var taro = hire("E0001", APR_1);
            assignments.save(Assignment.startingAt(taro, section, APR_1));

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO assignments (id, employee_id, department_id, valid_from)
                    VALUES (?, ?, ?, DATE '2020-01-01')
                    """, UUID.randomUUID(), taro.value(), division.value()))
                    .rootCause()
                    .hasMessageContaining("assignments_no_overlap");
        }
    }

    @Nested
    @DisplayName("組織図（実データでの導出）")
    class Chart {

        @Test
        @DisplayName("IT-EMP-25 所属・祖先・部署長を実データから導出できる")
        void derivation() {
            var taro = hire("E0001", APR_1);
            var boss = hire("E0002", APR_1);
            assignments.save(Assignment.startingAt(taro, section, APR_1));
            managerships.save(Managership.startingAt(division, boss, APR_1));

            assertThat(chart.departmentOf(taro, APR_1)).map(Department::name)
                    .contains("第一営業課");
            assertThat(chart.selfAndAncestorsOf(section)).extracting(Department::name)
                    .containsExactly("第一営業課", "第一営業部", "営業本部");
            assertThat(chart.managerOf(section, APR_1))
                    .as("課には長がいない").isEmpty();
            assertThat(chart.managerOf(division, APR_1)).map(Managership::employeeId)
                    .contains(boss);
            assertThat(chart.isActiveOn(taro, APR_1)).isTrue();
        }

        /**
         * <strong>月中入社の基準日</strong>（BR-11 の 1）。
         * この経路が実データで動かないと、月中入社の初月が永久に締められない。
         */
        @Test
        @DisplayName("IT-EMP-26 月の途中で始まった所属の開始日を返す")
        void assignmentStartWithin() {
            var joinedMidMonth = LocalDate.of(2026, 4, 15);
            var hanako = hire("E0004", joinedMidMonth);
            assignments.save(Assignment.startingAt(hanako, section, joinedMidMonth));

            assertThat(chart.assignmentStartWithin(hanako, YearMonth.of(2026, 4)))
                    .contains(joinedMidMonth);
            assertThat(chart.assignmentStartWithin(hanako, YearMonth.of(2026, 5)))
                    .as("翌月は月初から続いているので空").isEmpty();
        }
    }

    @Nested
    @DisplayName("DB の制約がドメインを守る")
    class Constraints {

        /** 兼務なし。1 社員の所属期間は重複しない。 */
        @Test
        @DisplayName("重複する所属は DB が拒否する")
        void overlappingAssignments() {
            var taro = hire("E0001", APR_1);
            assignments.save(Assignment.startingAt(taro, section, APR_1));

            assertThatThrownBy(() ->
                    assignments.save(Assignment.startingAt(taro, division,
                            LocalDate.of(2026, 6, 1))))
                    .rootCause()
                    .hasMessageContaining("assignments_no_overlap");
        }

        /** 1 つの部署に同時に 2 人の長はいない。 */
        @Test
        @DisplayName("重複する部署長は DB が拒否する")
        void overlappingManagerships() {
            var first = hire("E0002", APR_1);
            var second = hire("E0003", APR_1);
            managerships.save(Managership.startingAt(division, first, APR_1));

            assertThatThrownBy(() ->
                    managerships.save(Managership.startingAt(division, second,
                            LocalDate.of(2026, 6, 1))))
                    .rootCause()
                    .hasMessageContaining("managerships_no_overlap");
        }

        /** 入社日より前の所属は登録できない（トリガ）。 */
        @Test
        @DisplayName("入社日より前の所属は DB が拒否する")
        void assignmentBeforeHiring() {
            var taro = hire("E0001", APR_1);

            assertThatThrownBy(() ->
                    assignments.save(Assignment.startingAt(taro, section,
                            LocalDate.of(2026, 3, 1))))
                    .rootCause()
                    .hasMessageContaining("入社日より前の所属は登録できません");
        }
    }
}
