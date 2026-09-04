package jp.co.sample.kintai.employee.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeCredential;
import jp.co.sample.kintai.employee.domain.EmployeeCredentialRepository;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.PasswordHasher;
import jp.co.sample.kintai.employee.domain.RawPassword;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;

/**
 * 認証と認可の API（IT-AUTH-01〜12）。
 *
 * <p><strong>「認証をかけた」ことは、かかっていることを確かめないと分からない。</strong>
 * 設定を書いただけでは、経路がひとつ抜けても気づけない。
 * ここでは未認証・他人・CSRF なしの 3 つが実際に拒否されることを通しで確かめる。
 */
@DisplayName("認証と認可の API")
class AuthenticationApiTest extends WebIntegrationTestBase {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private EmployeeCredentialRepository credentials;
    @Autowired
    private PasswordHasher hasher;

    private EmployeeId taro;

    @BeforeEach
    void setUpEmployee() {
        taro = hire("E0001", "山田 太郎", Optional.empty(), Role.EMPLOYEE);
        setPassword(taro, PASSWORD);
    }

    private EmployeeId hire(String number, String name, Optional<LocalDate> retiredOn,
                            Role... roles) {
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber(number), name,
                new Email(number.toLowerCase() + "@example.com"), HIRED, retiredOn,
                Set.of(roles)));
        return id;
    }

    private void setPassword(EmployeeId id, String raw) {
        credentials.save(new EmployeeCredential(id, hasher.hash(new RawPassword(raw)),
                LocalDateTime.of(2026, 1, 1, 0, 0)));
    }

    private String signInBody(String number, String password) {
        return "{\"employeeNumber\":\"%s\",\"password\":\"%s\"}".formatted(number, password);
    }

    @Nested
    @DisplayName("ログイン")
    class SignIn {

        @Test
        @DisplayName("IT-AUTH-01 社員番号とパスワードでログインでき、ロールが返る")
        void succeeds() throws Exception {
            mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employeeNumber").value("E0001"))
                    .andExpect(jsonPath("$.name").value("山田 太郎"))
                    .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"));
        }

        /**
         * <strong>存在しない社員番号と、パスワード違いの応答が同じであること。</strong>
         * 区別すると、社員番号の総当たりで在籍者の一覧を作れる。
         */
        @Test
        @DisplayName("IT-AUTH-02 存在しない社員番号とパスワード違いは同じ応答になる")
        void failuresAreIndistinguishable() throws Exception {
            var unknown = mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E9999", PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            var wrongPassword = mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", "wrong-password-here")))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(unknown)
                    .as("どちらが違うかを応答から判別できてはならない")
                    .isEqualTo(wrongPassword)
                    .contains("urn:kintai:error:authentication-failed");
        }

        /** 退職者も同じ応答にする。在籍しているかを未認証の相手に教えない。 */
        @Test
        @DisplayName("IT-AUTH-03 退職済みの社員はログインできない")
        void retiredCannotSignIn() throws Exception {
            var retired = hire("E0002", "退職 太郎",
                    Optional.of(LocalDate.of(2026, 3, 31)), Role.EMPLOYEE);
            setPassword(retired, PASSWORD);

            mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0002", PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:authentication-failed"));
        }

        /**
         * <strong>短いパスワードでも 401 になる。</strong>
         * ここで規則（BR-13）を当てて 422 を返すと、
         * 「この社員番号は存在して、入力が短かっただけ」と読める応答になる。
         */
        @Test
        @DisplayName("IT-AUTH-04 規則を満たさない短いパスワードでも 401 になる")
        void shortPasswordIsStillUnauthorized() throws Exception {
            mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", "short")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:authentication-failed"));
        }

        /** ログイン後はセッションで認証が続く。 */
        @Test
        @DisplayName("IT-AUTH-05 ログインしたセッションで /api/me を取得できる")
        void sessionCarriesTheAuthentication() throws Exception {
            var session = new MockHttpSession();
            mockMvc.perform(post("/api/sessions").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", PASSWORD)))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/me").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.employeeNumber").value("E0001"));
        }

        @Test
        @DisplayName("IT-AUTH-06 ログアウトするとセッションが無効になる")
        void signOut() throws Exception {
            var session = new MockHttpSession();
            mockMvc.perform(post("/api/sessions").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", PASSWORD)))
                    .andExpect(status().isOk());

            mockMvc.perform(delete("/api/sessions").session(session).with(csrf()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/me").session(session))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("認証と CSRF の要求")
    class Guards {

        /**
         * <strong>未認証は 401 であって 302 ではない。</strong>
         * 既定のままだとログイン画面へリダイレクトし、
         * fetch から見ると「成功した HTML」が返る。
         */
        @Test
        @DisplayName("IT-AUTH-07 未認証で API を呼ぶと 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/attendances", taro.value())
                            .param("month", "2026-04"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * <strong>Cookie でセッションを持つので CSRF 対策を外せない。</strong>
         * M1-a では API に認証が無かったため無効にしていた。
         */
        @Test
        @DisplayName("IT-AUTH-08 CSRF トークンの無い更新は 403")
        void withoutCsrfToken() throws Exception {
            var principal = new jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee(
                    taro, "E0001", "山田 太郎", Set.of(Role.EMPLOYEE));
            mockMvc.perform(post("/api/employees/{id}/time-clocks", taro.value())
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.user(principal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"CLOCK_IN\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("閲覧範囲")
    class Scope {

        /** 一般社員は他人の勤怠を見られない（要件定義書 4.1）。 */
        @Test
        @DisplayName("IT-AUTH-09 一般社員が他人の勤怠を見ると 403")
        void otherEmployeesAttendance() throws Exception {
            var jiro = hire("E0002", "鈴木 次郎", Optional.empty(), Role.EMPLOYEE);

            mockMvc.perform(get("/api/employees/{id}/attendances", jiro.value())
                            .param("month", "2026-04")
                            .with(as(taro, "E0001", Role.EMPLOYEE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:forbidden"));
        }

        /** 人事は全社員を見られる。 */
        @Test
        @DisplayName("IT-AUTH-10 人事は他人の勤怠を見られる")
        void hrCanSeeEveryone() throws Exception {
            var jiro = hire("E0002", "鈴木 次郎", Optional.empty(), Role.EMPLOYEE);

            mockMvc.perform(get("/api/employees/{id}/attendances", jiro.value())
                            .param("month", "2026-04")
                            .with(as(taro, "E0001", Role.EMPLOYEE, Role.HR)))
                    .andExpect(status().isOk());
        }

        /**
         * <strong>打刻できるのは本人だけである。</strong>
         * 人事であっても他人の打刻は作れない。打刻は一次証拠であり、
         * 本人以外が作れると「その時刻にその人がいた」という記録の意味が消える。
         */
        @Test
        @DisplayName("IT-AUTH-11 人事でも他人の打刻はできない")
        void nobodyPunchesForSomeoneElse() throws Exception {
            var jiro = hire("E0002", "鈴木 次郎", Optional.empty(), Role.EMPLOYEE);

            mockMvc.perform(post("/api/employees/{id}/time-clocks", jiro.value())
                            .with(as(taro, "E0001", Role.EMPLOYEE, Role.HR))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"CLOCK_IN\","
                                    + "\"occurredAt\":\"2026-04-06T09:00:00\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("パスワード")
    class Passwords {

        @Test
        @DisplayName("IT-AUTH-12 本人はパスワードを変更でき、新しいパスワードでログインできる")
        void changeOwnPassword() throws Exception {
            mockMvc.perform(put("/api/me/password")
                            .with(as(taro, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{\"currentPassword\":\"%s\","
                                    + "\"newPassword\":\"new-password-1234\"}")
                                    .formatted(PASSWORD)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", "new-password-1234")))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("IT-AUTH-13 現在のパスワードが違うと 422")
        void wrongCurrentPassword() throws Exception {
            mockMvc.perform(put("/api/me/password")
                            .with(as(taro, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"wrong-password-here\","
                                    + "\"newPassword\":\"new-password-1234\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("urn:kintai:error:current-password-mismatch"));
        }

        /** 規則（BR-13）は設定の経路で当てる。 */
        @Test
        @DisplayName("IT-AUTH-14 短すぎる新しいパスワードは 422")
        void weakNewPassword() throws Exception {
            mockMvc.perform(put("/api/me/password")
                            .with(as(taro, "E0001", Role.EMPLOYEE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(("{\"currentPassword\":\"%s\","
                                    + "\"newPassword\":\"short\"}").formatted(PASSWORD)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value("urn:kintai:error:weak-password"));
        }

        @Test
        @DisplayName("IT-AUTH-15 ADMIN は現在のパスワード無しで再設定できる")
        void adminResets() throws Exception {
            var admin = hire("E0900", "管理 者", Optional.empty(),
                    Role.EMPLOYEE, Role.ADMIN);

            mockMvc.perform(put("/api/employees/{id}/password", taro.value())
                            .with(as(admin, "E0900", Role.EMPLOYEE, Role.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newPassword\":\"reissued-password-1\"}"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(signInBody("E0001", "reissued-password-1")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("IT-AUTH-16 ADMIN でなければ他人のパスワードを再設定できない")
        void nonAdminCannotReset() throws Exception {
            var jiro = hire("E0002", "鈴木 次郎", Optional.empty(), Role.EMPLOYEE);

            mockMvc.perform(put("/api/employees/{id}/password", jiro.value())
                            .with(as(taro, "E0001", Role.EMPLOYEE, Role.HR))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newPassword\":\"reissued-password-1\"}"))
                    .andExpect(status().isForbidden());
        }

        /** 平文の保存は DB が物理的に拒否する。保存されているのはハッシュだけ。 */
        @Test
        @DisplayName("IT-AUTH-17 保存されているのは BCrypt のハッシュで、平文ではない")
        void storesOnlyHashes() {
            String stored = jdbc.queryForObject(
                    "SELECT password_hash FROM employee_credentials WHERE employee_id = ?",
                    String.class, taro.value());

            assertThat(stored).startsWith("$2").hasSize(60).doesNotContain(PASSWORD);
        }
    }
}
