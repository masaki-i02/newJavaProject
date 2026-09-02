package jp.co.sample.kintai.employee.infrastructure;

import static jp.co.sample.kintai.support.ConstraintAssertions.accepted;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedBy;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedWithMessage;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Fixtures;
import jp.co.sample.kintai.support.IntegrationTestBase;

/**
 * 社員・組織の制約（IT-EMP-01〜14）。
 *
 * <p>対応する設計は {@code doc/02_詳細設計/01_社員・組織/DB設計書.md} の 7 章。
 */
@DisplayName("社員・組織の制約")
class EmployeeConstraintTest extends IntegrationTestBase {

    private Fixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
    }

    @Nested
    @DisplayName("社員")
    class Employees {

        @Test
        @DisplayName("IT-EMP-06 退職日を入社日より前にすると拒否される")
        void retiredBeforeHired() {
            rejectedBy("employees_employment_period_check",
                    () -> fixtures.employee("E0001", LocalDate.of(2026, 4, 1),
                            LocalDate.of(2026, 3, 31)));
        }

        @Test
        @DisplayName("IT-EMP-07 在籍者と大文字違いの同一メールは拒否される")
        void emailIsUniqueIgnoringCase() {
            fixtures.employee("E0001", LocalDate.of(2026, 4, 1));
            rejectedBy("employees_email_uk", () -> jdbc.update("""
                    INSERT INTO employees (id, employee_number, name, email, hired_on)
                    VALUES (?, 'E0002', '別人', 'E0001@EXAMPLE.COM', DATE '2026-04-01')
                    """, Fixtures.id()));
        }

        @Test
        @DisplayName("IT-EMP-08 退職者と同じメールで新規登録できる（再利用を認める）")
        void retiredEmployeeEmailCanBeReused() {
            fixtures.employee("E0001", LocalDate.of(2020, 4, 1), LocalDate.of(2026, 3, 31));
            accepted(() -> jdbc.update("""
                    INSERT INTO employees (id, employee_number, name, email, hired_on)
                    VALUES (?, 'E0002', '新しい人', 'e0001@example.com', DATE '2026-04-01')
                    """, Fixtures.id()));
        }

        @Test
        @DisplayName("IT-EMP-03 平文のパスワードを保存すると拒否される")
        void plainTextPasswordIsRejected() {
            UUID employee = fixtures.employee("E0001", LocalDate.of(2026, 4, 1));
            rejectedBy("employee_credentials_hash_format_check", () -> jdbc.update("""
                    INSERT INTO employee_credentials (employee_id, password_hash)
                    VALUES (?, 'password1234')
                    """, employee));
        }

        @Test
        @DisplayName("BCrypt のハッシュなら保存できる")
        void bcryptHashIsAccepted() {
            UUID employee = fixtures.employee("E0001", LocalDate.of(2026, 4, 1));
            accepted(() -> jdbc.update("""
                    INSERT INTO employee_credentials (employee_id, password_hash)
                    VALUES (?, '$2b$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0')
                    """, employee));
        }
    }

    @Nested
    @DisplayName("部署の階層")
    class DepartmentHierarchy {

        /**
         * {@code departments_no_self_parent_check} という CHECK もあるが、
         * <strong>BEFORE トリガのほうが先に評価される</strong>ため、
         * 実際に返るのは循環検出トリガのメッセージである（CLAUDE.md 落とし穴 9）。
         * どちらでも防げるが、利用者に見えるのはトリガの側なのでそちらを期待する。
         */
        @Test
        @DisplayName("IT-EMP-05 部署の親に自分自身を設定すると、循環検出トリガで拒否される")
        void selfParentIsRejected() {
            UUID department = fixtures.department("S1", "第一営業部");
            rejectedWithMessage("循環", () -> jdbc.update(
                    "UPDATE departments SET parent_id = id WHERE id = ?", department));
        }

        @Test
        @DisplayName("IT-EMP-04 部署の親子に多段の循環を作ると拒否される")
        void multiLevelCycleIsRejected() {
            UUID head = fixtures.department("H", "営業本部");
            UUID division = fixtures.department("S1", "第一営業部", head);
            UUID section = fixtures.department("S1A", "第一営業課", division);

            rejectedWithMessage("循環", () -> jdbc.update(
                    "UPDATE departments SET parent_id = ? WHERE id = ?", section, head));
        }
    }

    @Nested
    @DisplayName("所属と部署長")
    class AssignmentsAndManagerships {

        private UUID employee;
        private UUID department;

        @BeforeEach
        void setUpOrganization() {
            employee = fixtures.employee("E0001", LocalDate.of(2026, 4, 1));
            department = fixtures.department("S1A", "第一営業課");
        }

        @Test
        @DisplayName("IT-EMP-01 同一社員に期間の重なる所属を登録すると拒否される（兼務なし）")
        void overlappingAssignments() {
            fixtures.assign(employee, department, LocalDate.of(2026, 4, 1));
            UUID other = fixtures.department("S1B", "第二営業課");
            rejectedBy("assignments_no_overlap",
                    () -> fixtures.assign(employee, other, LocalDate.of(2026, 7, 1)));
        }

        @Test
        @DisplayName("IT-EMP-02 同一部署に期間の重なる部署長を登録すると拒否される")
        void overlappingManagerships() {
            UUID other = fixtures.employee("E0002", LocalDate.of(2026, 4, 1));
            fixtures.appointManager(department, employee, LocalDate.of(2026, 4, 1));
            rejectedBy("managerships_no_overlap",
                    () -> fixtures.appointManager(department, other, LocalDate.of(2026, 7, 1)));
        }

        @Test
        @DisplayName("IT-EMP-09 入社日より前の所属を登録すると拒否される")
        void assignmentBeforeHireDate() {
            rejectedWithMessage("入社日より前の所属は登録できません",
                    () -> fixtures.assign(employee, department, LocalDate.of(2026, 3, 1)));
        }

        @Test
        @DisplayName("IT-EMP-10 退職済みの社員を部署長に設定すると拒否される")
        void retiredEmployeeCannotBeManager() {
            UUID retired = fixtures.employee("E0003", LocalDate.of(2020, 4, 1),
                    LocalDate.of(2026, 3, 31));
            rejectedWithMessage("退職済みの社員を部署長に設定できません",
                    () -> fixtures.appointManager(department, retired, LocalDate.of(2026, 6, 1)));
        }

        @Test
        @DisplayName("IT-EMP-11 廃止済みの部署へ配属すると拒否される")
        void assignmentToAbolishedDepartment() {
            UUID abolished = fixtures.department("OLD", "旧部署");
            jdbc.update("UPDATE departments SET abolished_on = DATE '2026-04-01' WHERE id = ?",
                    abolished);
            rejectedWithMessage("廃止済みの部署へは配属できません",
                    () -> fixtures.assign(employee, abolished, LocalDate.of(2026, 5, 1)));
        }

        @Test
        @DisplayName("IT-EMP-12 期間を区切れば異動と部署長交代を登録できる")
        void movesAndHandoversAreAllowedWhenPeriodsAreClosed() {
            UUID other = fixtures.department("S1B", "第二営業課");
            fixtures.assign(employee, department, LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 7, 1));
            accepted(() -> fixtures.assign(employee, other, LocalDate.of(2026, 7, 1)));

            UUID successor = fixtures.employee("E0002", LocalDate.of(2026, 4, 1));
            fixtures.appointManager(department, employee, LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 7, 1));
            accepted(() -> fixtures.appointManager(department, successor,
                    LocalDate.of(2026, 7, 1)));
        }

        @Test
        @DisplayName("IT-EMP-13 対象日だけを変えると、その日の部署長が返る")
        void managerDependsOnTheDate() {
            UUID successor = fixtures.employee("E0002", LocalDate.of(2026, 4, 1));
            fixtures.appointManager(department, employee, LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 7, 1));
            fixtures.appointManager(department, successor, LocalDate.of(2026, 7, 1));

            // 入力のうち日付だけを変える（CLAUDE.md 落とし穴 12）
            assertThat(managerOn(department, LocalDate.of(2026, 6, 30))).isEqualTo(employee);
            assertThat(managerOn(department, LocalDate.of(2026, 7, 1))).isEqualTo(successor);
        }

        /** DB設計書 4.2 のクエリ。 */
        private UUID managerOn(UUID departmentId, LocalDate date) {
            return jdbc.queryForObject("""
                    SELECT employee_id FROM managerships
                     WHERE department_id = ?
                       AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)
                    """, UUID.class, departmentId, date, date);
        }
    }

    @Test
    @DisplayName("IT-EMP-14 updated_at が UPDATE で更新される")
    void updatedAtIsMaintained() {
        UUID employee = fixtures.employee("E0001", LocalDate.of(2026, 4, 1));
        assertThat(jdbc.queryForObject(
                "SELECT updated_at = created_at FROM employees WHERE id = ?",
                Boolean.class, employee)).isTrue();

        jdbc.update("UPDATE employees SET name = '山田 太郎' WHERE id = ?", employee);

        assertThat(jdbc.queryForObject(
                "SELECT updated_at > created_at FROM employees WHERE id = ?",
                Boolean.class, employee)).isTrue();
    }
}
