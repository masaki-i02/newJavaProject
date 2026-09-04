package jp.co.sample.kintai.employee.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
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
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;

/**
 * 異動・退職・ロールの API（IT-EMP-44〜58）。
 *
 * <p><strong>退職の副作用を通しで確かめるのがこのテストの中心である。</strong>
 * 部署長を閉じ忘れると、<strong>その部署の全社員の承認者が退職者になり続ける。</strong>
 * 既存の行が後から不正になる種類の問題なので、DB の制約では検出できない。
 */
@DisplayName("異動・退職・ロールの API")
class EmployeeLifecycleApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 1);

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

    private EmployeeId 山田;
    private EmployeeId 課長;
    private EmployeeId 管理者;
    private DepartmentId 営業部;
    private DepartmentId 総務部;

    @BeforeEach
    void setUpOrganization() {
        山田 = hire("E0001", "山田 太郎", "yamada@example.com", Role.EMPLOYEE);
        課長 = hire("E0100", "課長 次郎", "kacho@example.com", Role.EMPLOYEE);
        管理者 = hire("E0900", "管理 三郎", "admin@example.com",
                Role.EMPLOYEE, Role.ADMIN);

        営業部 = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(営業部, new DepartmentCode("SALES"), "営業部"));
        総務部 = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(総務部, new DepartmentCode("GA"), "総務部"));

        assignments.save(Assignment.startingAt(山田, 営業部, HIRED));
        assignments.save(Assignment.startingAt(課長, 営業部, HIRED));
        assignments.save(Assignment.startingAt(管理者, 総務部, HIRED));
        managerships.save(Managership.startingAt(営業部, 課長, HIRED));
    }

    private EmployeeId hire(String number, String name, String email, Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(email), HIRED, Optional.empty(), Set.of(roles)));
        return id;
    }

    private long versionOf(EmployeeId id) {
        return jdbc.queryForObject("SELECT version FROM employees WHERE id = ?",
                Long.class, id.value());
    }

    private ResultActions 管理者として(org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** その社員の、開いている部署長の件数。 */
    private int 開いている部署長(EmployeeId id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM managerships
                WHERE employee_id = ? AND valid_to IS NULL
                """, Integer.class, id.value());
    }

    /**
     * 直近の所属の終了日。開いていれば {@code null}。
     *
     * <p>{@code Stream.findFirst()} は<strong>先頭が null だと NPE を投げる。</strong>
     * 「開いている期間」を表すのがまさに null なので、添字で取り出す。
     */
    private LocalDate 所属の終了日(EmployeeId id) {
        List<LocalDate> found = jdbc.queryForList("""
                SELECT valid_to FROM assignments
                WHERE employee_id = ? ORDER BY valid_from DESC LIMIT 1
                """, LocalDate.class, id.value());
        return found.isEmpty() ? null : found.get(0);
    }

    @Nested
    @DisplayName("異動")
    class Transfer {

        @Test
        @DisplayName("IT-EMP-44 異動すると現在の所属が閉じ、新しい所属が開く")
        void transfer() throws Exception {
            管理者として(post("/api/employees/{id}/assignments", 山田.value()), """
                    {"departmentId":"%s","validFrom":"2026-05-01"}
                    """.formatted(総務部.value()))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/employees/{id}/assignments", 山田.value())
                            .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)))
                    .andExpect(status().isOk())
                    // ★ 新しい順。現在の所属は validTo が null
                    .andExpect(jsonPath("$.assignments[0].code").value("GA"))
                    .andExpect(jsonPath("$.assignments[0].validFrom").value("2026-05-01"))
                    .andExpect(jsonPath("$.assignments[0].validTo").doesNotExist())
                    .andExpect(jsonPath("$.assignments[1].code").value("SALES"))
                    .andExpect(jsonPath("$.assignments[1].validTo").value("2026-05-01"));
        }

        @Test
        @DisplayName("IT-EMP-45 入社日より前への異動は 422")
        void beforeHireDate() throws Exception {
            管理者として(post("/api/employees/{id}/assignments", 山田.value()), """
                    {"departmentId":"%s","validFrom":"2025-12-01"}
                    """.formatted(総務部.value()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:before-hire-date"));
        }

        @Test
        @DisplayName("IT-EMP-46 廃止済みの部署へは異動できない")
        void abolishedDepartment() throws Exception {
            departments.save(departments.findById(総務部).orElseThrow()
                    .abolish(LocalDate.of(2026, 4, 1)));

            管理者として(post("/api/employees/{id}/assignments", 山田.value()), """
                    {"departmentId":"%s","validFrom":"2026-05-01"}
                    """.formatted(総務部.value()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:department-abolished"));
        }

        @Test
        @DisplayName("IT-EMP-47 管理者でなければ異動できない")
        void onlyAdmin() throws Exception {
            mockMvc.perform(post("/api/employees/{id}/assignments", 山田.value())
                            .with(as(課長, "E0100", Role.EMPLOYEE, Role.APPROVER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"departmentId":"%s","validFrom":"2026-05-01"}
                                    """.formatted(総務部.value())))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("退職")
    class Retirement {

        /**
         * <strong>退職日の翌日で閉じる。</strong>
         * 退職日当日は在籍しているので、当日で閉じると
         * 最終日の勤怠の承認者が導出できなくなる。
         */
        @Test
        @DisplayName("IT-EMP-48 退職すると所属が退職日の翌日で閉じる")
        void closesAssignment() throws Exception {
            退職させる(山田, "2026-04-30")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.retiredOn").value("2026-04-30"))
                    .andExpect(jsonPath("$.closedAssignments").value(1));

            assertThat(所属の終了日(山田)).isEqualTo(LocalDate.of(2026, 5, 1));
        }

        /**
         * <strong>部署長も閉じる。</strong>
         * 忘れると、その部署に所属する全社員の承認者が退職者になり続ける。
         */
        @Test
        @DisplayName("IT-EMP-49 部署長を務めていた社員の退職で、部署長も閉じる")
        void closesManagership() throws Exception {
            assertThat(開いている部署長(課長)).isEqualTo(1);

            退職させる(課長, "2026-04-30")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closedManagerships").value(1));

            assertThat(開いている部署長(課長)).isZero();
            // 退職日当日はまだ部署長。翌日から空く
            assertThat(managerships.findEffective(営業部, LocalDate.of(2026, 4, 30)))
                    .as("退職日当日は在籍している").isPresent();
            assertThat(managerships.findEffective(営業部, LocalDate.of(2026, 5, 1)))
                    .as("翌日からは空く").isEmpty();
        }

        @Test
        @DisplayName("IT-EMP-50 二重の退職登録は 409")
        void alreadyRetired() throws Exception {
            退職させる(山田, "2026-04-30").andExpect(status().isOk());

            退職させる(山田, "2026-05-31")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:already-retired"));
        }

        @Test
        @DisplayName("IT-EMP-51 退職日が入社日より前だと 422")
        void beforeHireDate() throws Exception {
            退職させる(山田, "2025-12-31")
                    .andExpect(status().isUnprocessableContent());
        }

        /**
         * <strong>退職の取消は、閉じた所属と部署長を開き直す。</strong>
         * 誤登録の訂正にだけ使う。
         */
        @Test
        @DisplayName("IT-EMP-52 退職を取り消すと所属と部署長が開き直る")
        void cancelRetirement() throws Exception {
            退職させる(課長, "2026-04-30").andExpect(status().isOk());
            assertThat(開いている部署長(課長)).isZero();

            退職を取り消す(課長)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closedAssignments").value(1))
                    .andExpect(jsonPath("$.closedManagerships").value(1));

            assertThat(開いている部署長(課長)).isEqualTo(1);
            assertThat(所属の終了日(課長)).isNull();
            assertThat(jdbc.queryForList("SELECT retired_on FROM employees WHERE id = ?",
                    LocalDate.class, 課長.value()).get(0)).isNull();
        }

        /** 退職していない社員の取消は 409。 */
        @Test
        @DisplayName("IT-EMP-53 退職していない社員の取消は 409")
        void notRetired() throws Exception {
            退職を取り消す(山田)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:not-retired"));
        }

        /**
         * <strong>退職の取消は、退職とは無関係に閉じた過去の期間を巻き戻さない。</strong>
         * 閉じた日を指定して戻すので、異動で閉じた所属はそのまま残る。
         */
        @Test
        @DisplayName("IT-EMP-54 退職の取消は、異動で閉じた過去の所属を巻き戻さない")
        void cancelDoesNotReopenPastTransfers() throws Exception {
            管理者として(post("/api/employees/{id}/assignments", 山田.value()), """
                    {"departmentId":"%s","validFrom":"2026-03-01"}
                    """.formatted(総務部.value())).andExpect(status().isCreated());

            退職させる(山田, "2026-04-30").andExpect(status().isOk());
            退職を取り消す(山田)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closedAssignments").value(1));

            // 異動で閉じた営業部の所属は閉じたまま
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM assignments
                    WHERE employee_id = ? AND valid_to IS NOT NULL
                    """, Integer.class, 山田.value()))
                    .as("巻き戻すのは退職で閉じたぶんだけ").isEqualTo(1);
        }

        /** 版はクエリパラメータで送る。DELETE の本文は経路で落とされることがある。 */
        private ResultActions 退職を取り消す(EmployeeId id) throws Exception {
            return mockMvc.perform(delete("/api/employees/{id}/retirement", id.value())
                    .param("version", String.valueOf(versionOf(id)))
                    .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)));
        }

        private ResultActions 退職させる(EmployeeId id, String retiredOn) throws Exception {
            return 管理者として(post("/api/employees/{id}/retirement", id.value()),
                    "{\"retiredOn\":\"%s\",\"version\":%d}"
                            .formatted(retiredOn, versionOf(id)));
        }
    }

    @Nested
    @DisplayName("ロール")
    class Roles {

        @Test
        @DisplayName("IT-EMP-55 ロールを付与できる")
        void grant() throws Exception {
            ロールを変える(山田, "[\"HR\"]")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").value(
                            org.hamcrest.Matchers.containsInAnyOrder("EMPLOYEE", "HR")));
        }

        /** <strong>置き換えである。</strong> 送られた集合がそのロールの全体になる。 */
        @Test
        @DisplayName("IT-EMP-56 ロールは置き換えられ、EMPLOYEE は残る")
        void replaceKeepsEmployee() throws Exception {
            ロールを変える(山田, "[\"HR\"]").andExpect(status().isOk());

            ロールを変える(山田, "[]")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").value(
                            org.hamcrest.Matchers.contains("EMPLOYEE")));
        }

        /**
         * <strong>{@code APPROVER} は付与できない。</strong>
         * 実体は部署長を務めている事実であり、認証時に導出する。
         * ロールとして持たせると「部署長だがロールが無く 403」
         * 「ロールはあるが対象 0 件」という不整合が起きる。
         */
        @Test
        @DisplayName("IT-EMP-57 APPROVER は付与できない")
        void approverIsNotAssignable() throws Exception {
            ロールを変える(山田, "[\"APPROVER\"]")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:not-assignable-role"));
        }

        @Test
        @DisplayName("IT-EMP-58 管理者でなければロールを変えられない")
        void onlyAdmin() throws Exception {
            mockMvc.perform(put("/api/employees/{id}/roles", 山田.value())
                            .with(as(課長, "E0100", Role.EMPLOYEE, Role.APPROVER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roles\":[\"HR\"],\"version\":%d}"
                                    .formatted(versionOf(山田))))
                    .andExpect(status().isForbidden());
        }

        private ResultActions ロールを変える(EmployeeId id, String roles) throws Exception {
            return 管理者として(put("/api/employees/{id}/roles", id.value()),
                    "{\"roles\":%s,\"version\":%d}".formatted(roles, versionOf(id)));
        }
    }
}
