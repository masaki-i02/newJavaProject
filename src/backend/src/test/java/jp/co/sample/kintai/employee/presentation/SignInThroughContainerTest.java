package jp.co.sample.kintai.employee.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import jp.co.sample.kintai.support.PostgresSupport;

/**
 * まっさらなブラウザからの最初のログイン（IT-AUTH-18・19）。
 *
 * <p><strong>MockMvc では通らない経路がある。</strong>
 * {@code MockHttpServletRequest} は要求ごとにセッションを用意するので、
 * 「セッションがまだ 1 つも無い状態」を再現できない。
 *
 * <p>実際には、CSRF トークンをクッキーに持たせている
 * （{@code CookieCsrfTokenRepository}）のでセッションは要らず、
 * <strong>最初のログインの時点でセッションが存在しない。</strong>
 * そこで {@code changeSessionId()} を呼ぶと
 * {@code IllegalStateException} になり、<strong>500 が返る。</strong>
 * つまり、まっさらなブラウザからは 1 度もログインできない状態だった。
 *
 * <p>MockMvc のテスト 12 件はすべて緑のままだったので、
 * <strong>実物のサーブレットコンテナを立てて確かめる。</strong>
 * 1 つだけコンテキストが増えるが、この経路は他では踏めない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("まっさらなブラウザからのログイン")
class SignInThroughContainerTest {

    private static final String PASSWORD = "correct-horse-battery";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    /**
     * ★ 素の {@code HttpClient} を使う。
     *   クッキーを 1 つも持たない要求を送りたいので、
     *   クッキーを引き継ぐ仕組みが挟まらないほうがよい。
     */
    @LocalServerPort
    private int port;

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private EmployeeCredentialRepository credentials;
    @Autowired
    private PasswordHasher hasher;

    @Autowired
    private javax.sql.DataSource dataSource;

    @BeforeEach
    void setUpEmployee() {
        // ★ この土台は WebIntegrationTestBase を継承しない（MockMvc ではないため）。
        //   後始末も自分で行う
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute(
                "TRUNCATE TABLE employee_credentials, employee_roles, employees CASCADE");
        var id = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(id, new EmployeeNumber("E0001"), "山田 太郎",
                new Email("e0001@example.com"), LocalDate.of(2026, 1, 1),
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        credentials.save(new EmployeeCredential(id, hasher.hash(new RawPassword(PASSWORD)),
                LocalDateTime.of(2026, 1, 1, 9, 0)));
    }

    /**
     * <strong>クッキーを 1 つも持たない要求でログインできる。</strong>
     *
     * <p>セッション固定攻撃の対策（{@code changeSessionId}）は、
     * <strong>作り直す相手がいるときだけ</strong>意味を持つ。
     * 相手がいないときに呼ぶと例外になる。
     */
    @Test
    @DisplayName("IT-AUTH-18 セッションを持たない最初の要求でログインできる")
    void signsInWithoutAnyExistingSession() throws Exception {
        var response = signIn(PASSWORD);

        assertThat(response.statusCode())
                .as("500 になっていたら changeSessionId の呼び方が誤っている")
                .isEqualTo(200);
        assertThat(response.body()).contains("E0001");
        assertThat(response.headers().allValues("set-cookie"))
                .as("セッションと CSRF トークンが配られる")
                .anySatisfy(cookie -> assertThat(cookie).contains("XSRF-TOKEN"));
    }

    /** 失敗しても 500 にならない。理由は区別しない（BR-13）。 */
    @Test
    @DisplayName("IT-AUTH-19 セッションを持たない要求でのログイン失敗は 401")
    void failsWithUnauthorizedNotServerError() throws Exception {
        assertThat(signIn("wrong-password-x").statusCode()).isEqualTo(401);
    }

    private java.net.http.HttpResponse<String> signIn(String password) throws Exception {
        var request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(
                        "http://localhost:%d/api/sessions".formatted(port)))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"employeeNumber\":\"E0001\",\"password\":\"%s\"}"
                                .formatted(password), java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
