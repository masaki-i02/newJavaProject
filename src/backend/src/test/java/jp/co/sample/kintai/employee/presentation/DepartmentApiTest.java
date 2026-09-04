package jp.co.sample.kintai.employee.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * 部署ツリーの API（IT-EMP-59〜72）。
 *
 * <pre>
 * 本部（HQ）           長: 本部長
 *   └ 営業部（SALES）  長: 課長
 *       └ 第一課（S1）  長: なし
 * </pre>
 */
@DisplayName("部署ツリーの API")
class DepartmentApiTest extends WebIntegrationTestBase {

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
    private ManagershipRepository managerships;

    private EmployeeId 山田;
    private EmployeeId 課長;
    private EmployeeId 本部長;
    private EmployeeId 管理者;
    private DepartmentId 本部;
    private DepartmentId 営業部;
    private DepartmentId 第一課;

    @BeforeEach
    void setUpOrganization() {
        山田 = hire("E0001", "山田 太郎", Role.EMPLOYEE);
        課長 = hire("E0100", "課長 次郎", Role.EMPLOYEE);
        本部長 = hire("E0200", "本部長 一郎", Role.EMPLOYEE);
        管理者 = hire("E0900", "管理 三郎", Role.EMPLOYEE, Role.ADMIN);

        本部 = department("HQ", "本部", null);
        営業部 = department("SALES", "営業部", 本部);
        第一課 = department("S1", "第一課", 営業部);

        managerships.save(Managership.startingAt(本部, 本部長, HIRED));
        managerships.save(Managership.startingAt(営業部, 課長, HIRED));
    }

    private EmployeeId hire(String number, String name, Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED,
                Optional.empty(), Set.of(roles)));
        return id;
    }

    private DepartmentId department(String code, String name, DepartmentId parent) {
        var id = new DepartmentId(UUID.randomUUID());
        departments.save(parent == null
                ? Department.root(id, new DepartmentCode(code), name)
                : Department.under(id, new DepartmentCode(code), name, parent));
        return id;
    }

    private ResultActions 管理者として(MockHttpServletRequestBuilder request, String body)
            throws Exception {
        return mockMvc.perform(request.with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ツリーを見る(EmployeeId actor, String number, Role... roles)
            throws Exception {
        return mockMvc.perform(get("/api/departments").with(as(actor, number, roles)));
    }

    @Nested
    @DisplayName("ツリーの参照")
    class Tree {

        @Test
        @DisplayName("IT-EMP-59 管理者は全社のツリーを見られ、部署長も就任日つきで返る")
        void wholeTree() throws Exception {
            ツリーを見る(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.departments.length()").value(1))
                    .andExpect(jsonPath("$.departments[0].code").value("HQ"))
                    .andExpect(jsonPath("$.departments[0].manager.name").value("本部長 一郎"))
                    .andExpect(jsonPath("$.departments[0].manager.since").value("2026-01-01"))
                    .andExpect(jsonPath("$.departments[0].children[0].code").value("SALES"))
                    .andExpect(jsonPath("$.departments[0].children[0].children[0].code")
                            .value("S1"))
                    // 長が未設定の部署は manager が null
                    .andExpect(jsonPath("$.departments[0].children[0].children[0].manager")
                            .doesNotExist());
        }

        /**
         * <strong>一般社員には組織図を見せない。</strong>
         * 誰が誰の下にいるかは人事情報であり、勤怠の記録・提出に要らない。
         *
         * <p>判定はロールではなく<strong>その日に長を務めている事実</strong>で行う。
         * {@code APPROVER} は認証時に {@code managerships} から導出される値なので、
         * ロールを先に見ても同じことを二度訊くだけになる。
         */
        @Test
        @DisplayName("IT-EMP-60 一般社員は組織図を見られない")
        void employeeCannotSeeTheTree() throws Exception {
            ツリーを見る(山田, "E0001", Role.EMPLOYEE)
                    .andExpect(status().isForbidden());
        }

        /**
         * <strong>承認者は自分が長を務める部署以下だけ。</strong>
         * 親が対象に含まれないので、自部署が根として現れる。
         */
        @Test
        @DisplayName("IT-EMP-61 承認者は自分が長を務める部署以下だけを見る")
        void approverSeesOwnSubtree() throws Exception {
            ツリーを見る(課長, "E0100", Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.departments.length()").value(1))
                    // 本部は見えない。自部署が根になる
                    .andExpect(jsonPath("$.departments[0].code").value("SALES"))
                    .andExpect(jsonPath("$.departments[0].children[0].code").value("S1"));
        }

        /** ロールはあっても、その日に長を務めていなければ見られない。 */
        @Test
        @DisplayName("IT-EMP-62 長を務めていない承認者は見られない")
        void approverWithoutDepartment() throws Exception {
            ツリーを見る(山田, "E0001", Role.EMPLOYEE, Role.APPROVER)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("IT-EMP-63 廃止済みの部署は既定で含まれない")
        void abolishedIsHiddenByDefault() throws Exception {
            departments.save(departments.findById(第一課).orElseThrow()
                    .abolish(LocalDate.of(2026, 3, 1)));

            ツリーを見る(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)
                    .andExpect(jsonPath("$.departments[0].children[0].children.length()")
                            .value(0));

            mockMvc.perform(get("/api/departments").param("includeAbolished", "true")
                            .with(as(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)))
                    .andExpect(jsonPath("$.departments[0].children[0].children[0].code")
                            .value("S1"));
        }
    }

    @Nested
    @DisplayName("登録と更新")
    class Maintenance {

        @Test
        @DisplayName("IT-EMP-64 部署を登録できる")
        void create() throws Exception {
            管理者として(post("/api/departments"), """
                    {"code":"S2","name":"第二課","parentId":"%s"}
                    """.formatted(営業部.value()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("S2"));
        }

        @Test
        @DisplayName("IT-EMP-65 部署コードが現存部署と重複すると 409")
        void duplicateCode() throws Exception {
            管理者として(post("/api/departments"), """
                    {"code":"SALES","name":"別の営業部","parentId":null}
                    """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:duplicate-department-code"));
        }

        /** 廃止済みのコードは<strong>再利用できる。</strong> */
        @Test
        @DisplayName("IT-EMP-66 廃止済みの部署コードは再利用できる")
        void abolishedCodeIsReusable() throws Exception {
            departments.save(departments.findById(第一課).orElseThrow()
                    .abolish(LocalDate.of(2026, 3, 1)));

            管理者として(post("/api/departments"),
                    "{\"code\":\"S1\",\"name\":\"新第一課\",\"parentId\":null}")
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("IT-EMP-67 名称と親を更新できる")
        void update() throws Exception {
            管理者として(patch("/api/departments/{id}", 第一課.value()), """
                    {"code":"S1","name":"第一営業課","parentId":"%s"}
                    """.formatted(本部.value()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("第一営業課"));

            // 親が本部へ移ったので、営業部の下からは消える
            ツリーを見る(管理者, "E0900", Role.EMPLOYEE, Role.ADMIN)
                    .andExpect(jsonPath("$.departments[0].children.length()").value(2));
        }

        /**
         * <strong>循環は業務エラーとして返す。</strong>
         * DB のトリガも同じことを守っているが、
         * PL/pgSQL の {@code RAISE} は制約違反ではないので Problem Details に写らず、
         * <strong>親の選び間違いが 500 になる。</strong>
         * トリガは最後の防波堤として残す。
         */
        @Test
        @DisplayName("IT-EMP-68 親に自分の配下を指定すると 422 になる")
        void cycleIsRejected() throws Exception {
            管理者として(patch("/api/departments/{id}", 本部.value()), """
                    {"code":"HQ","name":"本部","parentId":"%s"}
                    """.formatted(第一課.value()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:cyclic-department-hierarchy"));

            assertThat(jdbc.queryForList("""
                    SELECT parent_id FROM departments WHERE id = ?
                    """, UUID.class, 本部.value()).get(0)).isNull();
        }

        @Test
        @DisplayName("IT-EMP-69 管理者でなければ部署を登録できない")
        void onlyAdmin() throws Exception {
            mockMvc.perform(post("/api/departments")
                            .with(as(課長, "E0100", Role.EMPLOYEE, Role.APPROVER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"X\",\"name\":\"勝手部\",\"parentId\":null}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("廃止と部署長")
    class AbolitionAndManagership {

        /**
         * <strong>配下に現存する部署があれば廃止できない。</strong>
         * 親だけを廃止すると、子が親のいない部署として残り、
         * 承認者の遡りが根へ到達できなくなる。
         */
        @Test
        @DisplayName("IT-EMP-70 配下に現存する部署があると廃止できない")
        void cannotAbolishWithLivingChildren() throws Exception {
            管理者として(post("/api/departments/{id}/abolition", 営業部.value()),
                    "{\"abolishedOn\":\"2026-05-01\"}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:department-has-children"));

            // 葉から順に廃止すれば通る
            管理者として(post("/api/departments/{id}/abolition", 第一課.value()),
                    "{\"abolishedOn\":\"2026-05-01\"}")
                    .andExpect(status().isOk());
            管理者として(post("/api/departments/{id}/abolition", 営業部.value()),
                    "{\"abolishedOn\":\"2026-05-01\"}")
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("IT-EMP-71 部署長を交代させると、現任の期間が閉じる")
        void appointManager() throws Exception {
            管理者として(post("/api/departments/{id}/managerships", 営業部.value()), """
                    {"employeeId":"%s","validFrom":"2026-05-01"}
                    """.formatted(山田.value()))
                    .andExpect(status().isCreated());

            assertThat(managerships.findEffective(営業部, LocalDate.of(2026, 4, 30))
                    .orElseThrow().employeeId()).as("交代日の前は現任").isEqualTo(課長);
            assertThat(managerships.findEffective(営業部, LocalDate.of(2026, 5, 1))
                    .orElseThrow().employeeId()).as("交代日から新任").isEqualTo(山田);
        }

        @Test
        @DisplayName("IT-EMP-72 退職済みの社員は部署長にできない")
        void retiredEmployeeCannotBeManager() throws Exception {
            employees.save(new Employee(山田, new EmployeeNumber("E0001"), "山田 太郎",
                    new Email("e0001@example.com"), HIRED,
                    Optional.of(LocalDate.of(2026, 4, 30)), Set.of(Role.EMPLOYEE)));

            管理者として(post("/api/departments/{id}/managerships", 営業部.value()), """
                    {"employeeId":"%s","validFrom":"2026-05-01"}
                    """.formatted(山田.value()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:retired-employee"));
        }
    }
}
