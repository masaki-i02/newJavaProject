package jp.co.sample.kintai.employee.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
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
 * 社員名簿の API（IT-EMP-27〜42）。
 *
 * <p><strong>閲覧範囲は「見える」と「見えない」の両方を検証する</strong>
 * （結合テスト仕様書 4）。画面が隠していても、API が返せば漏洩する。
 *
 * <pre>
 * 本部（HQ）          長: 本部長
 *   └ 営業部（SALES） 長: 課長
 *       └ 山田
 * 総務部（ADMIN_DEPT） 長: なし  ← 課長からは見えない
 *       └ 佐藤
 * </pre>
 */
@DisplayName("社員名簿の API")
class EmployeeApiTest extends WebIntegrationTestBase {

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
    private EmployeeId 佐藤;
    private EmployeeId 管理者;
    private DepartmentId 営業部;
    private DepartmentId 総務部;

    @BeforeEach
    void setUpOrganization() {
        山田 = hire("E0001", "山田 太郎", "yamada@example.com", Role.EMPLOYEE);
        課長 = hire("E0100", "課長 次郎", "kacho@example.com", Role.EMPLOYEE);
        佐藤 = hire("E0200", "佐藤 花子", "sato@example.com", Role.EMPLOYEE);
        管理者 = hire("E0900", "管理 三郎", "admin@example.com",
                Role.EMPLOYEE, Role.ADMIN);

        var 本部 = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(本部, new DepartmentCode("HQ"), "本部"));
        営業部 = new DepartmentId(UUID.randomUUID());
        departments.save(Department.under(営業部, new DepartmentCode("SALES"), "営業部", 本部));
        総務部 = new DepartmentId(UUID.randomUUID());
        departments.save(Department.root(総務部, new DepartmentCode("GA"), "総務部"));

        assignments.save(Assignment.startingAt(山田, 営業部, HIRED));
        assignments.save(Assignment.startingAt(課長, 営業部, HIRED));
        assignments.save(Assignment.startingAt(佐藤, 総務部, HIRED));
        assignments.save(Assignment.startingAt(管理者, 総務部, HIRED));
        managerships.save(Managership.startingAt(営業部, 課長, HIRED));
    }

    private EmployeeId hire(String number, String name, String email, Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(email), HIRED, Optional.empty(), Set.of(roles)));
        return id;
    }

    private ResultActions 一覧(EmployeeId actor, String number, Role... roles)
            throws Exception {
        return mockMvc.perform(get("/api/employees").with(as(actor, number, roles)));
    }

    private ResultActions 登録する(String body) throws Exception {
        return mockMvc.perform(post("/api/employees")
                .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long versionOf(EmployeeId id) {
        return jdbc.queryForObject("SELECT version FROM employees WHERE id = ?",
                Long.class, id.value());
    }

    @Nested
    @DisplayName("閲覧範囲")
    class Visibility {

        /** <strong>一般社員は自分だけ。</strong> 組織図は人事情報であり、勤怠の記録に要らない。 */
        @Test
        @DisplayName("IT-EMP-27 一般社員の一覧は自分だけ")
        void employeeSeesOnlySelf() throws Exception {
            一覧(山田, "E0001", Role.EMPLOYEE)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employees.length()").value(1))
                    .andExpect(jsonPath("$.employees[0].employeeNumber").value("E0001"));
        }

        /** 承認者は自分と、自分が長を務める部署の配下。 */
        @Test
        @DisplayName("IT-EMP-28 承認者の一覧は自分と配下だけ")
        void approverSeesSubordinates() throws Exception {
            一覧(課長, "E0100", Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employees.length()").value(2))
                    .andExpect(jsonPath("$.employees[?(@.employeeNumber=='E0001')]")
                            .exists())
                    .andExpect(jsonPath("$.employees[?(@.employeeNumber=='E0200')]")
                            .doesNotExist());
        }

        @Test
        @DisplayName("IT-EMP-29 人事・管理者の一覧は全社員")
        void humanResourcesSeesEveryone() throws Exception {
            一覧(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employees.length()").value(4));
        }

        /** <strong>詳細でも同じ範囲を守る。</strong> 一覧だけ絞っても、詳細から漏れる。 */
        @Test
        @DisplayName("IT-EMP-30 範囲外の社員の詳細は 403")
        void detailIsAlsoScoped() throws Exception {
            mockMvc.perform(get("/api/employees/{id}", 佐藤.value())
                            .with(as(課長, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/employees/{id}", 山田.value())
                            .with(as(課長, "E0100", Role.EMPLOYEE, Role.APPROVER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("山田 太郎"));
        }

        @Test
        @DisplayName("IT-EMP-31 未認証では 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(get("/api/employees"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("一覧の絞り込み")
    class Filtering {

        @Test
        @DisplayName("IT-EMP-32 部署を指定すると、その配下だけが返る")
        void byDepartment() throws Exception {
            mockMvc.perform(get("/api/employees").param("departmentId",
                            営業部.value().toString())
                            .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employees.length()").value(2));
        }

        /**
         * <strong>所属を持たない社員も返す。</strong>
         * 未来日入社の社員が登録直後の一覧に現れないと、
         * 管理者は登録の成否を確認できない。
         */
        @Test
        @DisplayName("IT-EMP-33 未来日入社の社員は所属が null で返る")
        void futureHireHasNoDepartment() throws Exception {
            登録する("""
                    {"employeeNumber":"E0300","name":"新人 四郎",
                     "email":"newcomer@example.com","hiredOn":"2026-10-01",
                     "departmentId":"%s"}
                    """.formatted(営業部.value())).andExpect(status().isCreated());

            mockMvc.perform(get("/api/employees")
                            .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)))
                    .andExpect(jsonPath(
                            "$.employees[?(@.employeeNumber=='E0300')].department")
                            .value(org.hamcrest.Matchers.contains(
                                    org.hamcrest.Matchers.nullValue())));
        }

        /** 退職者は既定で含めない。 */
        @Test
        @DisplayName("IT-EMP-34 退職者は includeRetired で切り替わる")
        void retiredEmployees() throws Exception {
            employees.save(new Employee(佐藤, new EmployeeNumber("E0200"), "佐藤 花子",
                    new Email("sato@example.com"), HIRED,
                    Optional.of(LocalDate.of(2026, 3, 31)), Set.of(Role.EMPLOYEE)));

            一覧(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)
                    .andExpect(jsonPath("$.employees.length()").value(3));

            mockMvc.perform(get("/api/employees").param("includeRetired", "true")
                            .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)))
                    .andExpect(jsonPath("$.employees.length()").value(4));
        }
    }

    @Nested
    @DisplayName("登録")
    class Registration {

        @Test
        @DisplayName("IT-EMP-35 管理者は社員を登録でき、所属も同時に作られる")
        void register() throws Exception {
            登録する("""
                    {"employeeNumber":"E0300","name":"新人 四郎",
                     "email":"newcomer@example.com","hiredOn":"2026-04-01",
                     "departmentId":"%s"}
                    """.formatted(営業部.value()))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.employeeNumber").value("E0300"))
                    .andExpect(jsonPath("$.department.code").value("SALES"))
                    // ★ EMPLOYEE はサーバが無条件に付与する
                    .andExpect(jsonPath("$.roles").value(
                            org.hamcrest.Matchers.contains("EMPLOYEE")));

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM assignments a
                      JOIN employees e ON e.id = a.employee_id
                     WHERE e.employee_number = 'E0300'
                    """, Integer.class)).isEqualTo(1);
        }

        /** <strong>追加ロールだけを指定できる。</strong> */
        @Test
        @DisplayName("IT-EMP-36 追加ロールを指定できる")
        void additionalRoles() throws Exception {
            登録する("""
                    {"employeeNumber":"E0301","name":"人事 五郎",
                     "email":"hr2@example.com","hiredOn":"2026-04-01",
                     "departmentId":"%s","additionalRoles":["HR"]}
                    """.formatted(営業部.value()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.roles").value(
                            org.hamcrest.Matchers.containsInAnyOrder("EMPLOYEE", "HR")));
        }

        /**
         * <strong>一意制約違反ではなく業務エラーとして返す。</strong>
         * どの項目が重複しているかを利用者に説明できるようにする。
         */
        @Test
        @DisplayName("IT-EMP-37 社員番号が在籍者と重複すると 409")
        void duplicateNumber() throws Exception {
            登録する("""
                    {"employeeNumber":"E0001","name":"別人 六郎",
                     "email":"other@example.com","hiredOn":"2026-04-01",
                     "departmentId":"%s"}
                    """.formatted(営業部.value()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:duplicate-employee-number"));
        }

        /**
         * メールは<strong>大文字小文字を区別しない。</strong>
         *
         * <p>畳んでいるのは {@code Email} の生成時であって、重複検査ではない。
         * 正規化を型に持たせているので、比較する側は素直に等値で比べられる。
         */
        @Test
        @DisplayName("IT-EMP-38 大文字違いの同一メールも 409")
        void duplicateEmailIgnoringCase() throws Exception {
            登録する("""
                    {"employeeNumber":"E0302","name":"別人 七郎",
                     "email":"YAMADA@example.com","hiredOn":"2026-04-01",
                     "departmentId":"%s"}
                    """.formatted(営業部.value()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:duplicate-email"));
        }

        @Test
        @DisplayName("IT-EMP-39 管理者でなければ登録できない")
        void onlyAdminCanRegister() throws Exception {
            mockMvc.perform(post("/api/employees")
                            .with(as(課長, "E0100", Role.EMPLOYEE, Role.APPROVER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"employeeNumber":"E0303","name":"勝手 八郎",
                                     "email":"x@example.com","hiredOn":"2026-04-01",
                                     "departmentId":"%s"}
                                    """.formatted(営業部.value())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("IT-EMP-40 存在しない部署を指定すると 404")
        void unknownDepartment() throws Exception {
            登録する("""
                    {"employeeNumber":"E0304","name":"新人 九郎",
                     "email":"n9@example.com","hiredOn":"2026-04-01",
                     "departmentId":"%s"}
                    """.formatted(UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }

    }

    @Nested
    @DisplayName("更新")
    class Updating {

        @Test
        @DisplayName("IT-EMP-41 氏名とメールを更新でき、版が進む")
        void update() throws Exception {
            long before = versionOf(山田);

            更新する(山田, """
                    {"name":"山田 花子","email":"yamada.hanako@example.com",
                     "version":%d}
                    """.formatted(before))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("山田 花子"))
                    // 社員番号と入社日は変わらない
                    .andExpect(jsonPath("$.employeeNumber").value("E0001"))
                    .andExpect(jsonPath("$.hiredOn").value("2026-01-01"));

            assertThat(versionOf(山田)).isGreaterThan(before);
        }

        /** <strong>古い版での更新は拒否する。</strong> */
        @Test
        @DisplayName("IT-EMP-42 古い版で更新すると 409 になり、値は変わらない")
        void staleVersion() throws Exception {
            更新する(山田, """
                    {"name":"山田 花子","email":"yamada.hanako@example.com","version":999}
                    """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:optimistic-lock-failure"));

            assertThat(jdbc.queryForObject(
                    "SELECT name FROM employees WHERE id = ?", String.class,
                    山田.value())).isEqualTo("山田 太郎");
        }

        @Test
        @DisplayName("IT-EMP-43 版を含まない更新は 400")
        void versionIsRequired() throws Exception {
            更新する(山田, "{\"name\":\"山田 花子\",\"email\":\"y@example.com\"}")
                    .andExpect(status().isBadRequest());
        }

        private ResultActions 更新する(EmployeeId id, String body) throws Exception {
            return mockMvc.perform(patch("/api/employees/{id}", id.value())
                    .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN))
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }
    }
}
