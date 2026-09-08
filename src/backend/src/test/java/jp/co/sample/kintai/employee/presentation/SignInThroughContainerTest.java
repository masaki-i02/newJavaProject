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
     * <strong>ログイン画面そのものが未認証で配信される。</strong>
     *
     * <p>画面は像のビルドで {@code classpath:/static/} へ同梱される
     * （{@code src/backend/Dockerfile}）。ところが {@code anyRequest().denyAll()} は
     * {@code /} も {@code /assets/**} も塞ぐので、開けておかないと
     * <strong>ログイン画面が本文の無い 403 になり、誰も入り口へ辿り着けない</strong>
     * （落とし穴 139：設定を書いただけの口は、開いているように見えるだけである）。
     *
     * <p>テストでは {@code src/test/resources/static/} の代役を配信する。
     * test の資源は {@code bootJar} に入らないので、本番の像には混ざらない。
     */
    @Test
    @DisplayName("IT-OPS-13 未認証でログイン画面が配信される")
    void servesTheSignInPageWithoutAuthentication() throws Exception {
        var response = get("/");

        assertThat(response.statusCode())
                .as("403 なら SecurityConfig が画面を塞いでいる")
                .isEqualTo(200);
        assertThat(response.body()).contains("KINTAI_STATIC_PROBE");
    }

    /** 画面が読み込む JavaScript も同じ入り口から届く。 */
    @Test
    @DisplayName("IT-OPS-14 未認証で画面の資産（assets）が配信される")
    void servesTheAssetsWithoutAuthentication() throws Exception {
        var response = get("/assets/probe.js");

        assertThat(response.statusCode())
                .as("HTML だけ開けても、資産が 403 なら白い画面になる")
                .isEqualTo(200);
        assertThat(response.body()).contains("KINTAI_ASSET_PROBE");
    }

    /**
     * <strong>開けたのは画面の 2 経路だけである。</strong>
     *
     * <p>{@code /**} をまとめて開けると、あとから増えたサーバ側の経路が
     * 黙って未認証で開く。<strong>開けた経路は「無ければ 404」</strong>になるので、
     * 401 が返ることが、まだ {@code denyAll} に落ちている証拠になる
     * （未認証の拒否は {@code HttpStatusEntryPoint} が 401 に写す）。
     */
    @Test
    @DisplayName("IT-OPS-15 画面の 2 経路の外は、開いていない")
    void doesNotOpenEverythingElse() throws Exception {
        assertThat(get("/application.yaml").statusCode())
                .as("404 なら permitAll の範囲が広すぎる（開いた経路は無ければ 404）")
                .isEqualTo(401);
        // ★ 認証と CSRF トークンを持たせてから POST する。
        //   持たせないと CSRF が先に 403 で拒み、GET 限定が効いているかを
        //   一切検査しないテストになる（落とし穴 137）。
        //   トークンを持たせると、静的資源のハンドラは POST に 405 を返すので、
        //   403（認可が拒んだ）と 405（ハンドラまで届いた）で区別がつく
        assertThat(postAsSignedInUser("/index.html").statusCode())
                .as("405 なら GET 限定が効いていない（ハンドラまで届いている）")
                .isEqualTo(403);
        assertThat(get("/actuator/env").statusCode())
                .as("設定値の一覧は未認証でも認証済みでも出さない")
                .isEqualTo(401);
    }

    /** ログインして得たセッションと CSRF トークンを載せて POST する。 */
    private java.net.http.HttpResponse<String> postAsSignedInUser(String path)
            throws Exception {
        var signedIn = signIn(PASSWORD);
        var cookies = signedIn.headers().allValues("set-cookie").stream()
                .map(cookie -> cookie.split(";", 2)[0])
                .toList();
        String token = cookies.stream()
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .map(cookie -> cookie.substring("XSRF-TOKEN=".length()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ログインで CSRF トークンが配られていない: " + cookies));

        return send(java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(
                        "http://localhost:%d%s".formatted(port, path)))
                .header("Cookie", String.join("; ", cookies))
                .header("X-XSRF-TOKEN", token)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build());
    }

    private java.net.http.HttpResponse<String> get(String path) throws Exception {
        return send(java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(
                        "http://localhost:%d%s".formatted(port, path)))
                .GET().build());
    }

    private java.net.http.HttpResponse<String> send(java.net.http.HttpRequest request)
            throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8));
        }
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

    /**
     * <strong>ディスパッチャの手前で拒まれた要求も、理由が読める形で返る。</strong>
     *
     * <p>{@code StrictHttpFirewall} は {@code DispatcherServlet} より前で拒むので、
     * {@code @ExceptionHandler} は一度も呼ばれない。
     * サーブレットの ERROR 転送で {@code /error} へ回されるが、
     * その転送を {@code anyRequest().denyAll()} が拒むと
     * <strong>本文の無い 403</strong> になる。
     * 「権限が無い」と「URL が不正」を取り違える。
     *
     * <p>これが {@code SecurityConfig} の
     * {@code dispatcherTypeMatchers(ERROR).permitAll()} が守っている唯一の経路である。
     * <strong>MockMvc では踏めない</strong>（ERROR 転送を再現しない）。
     */
    @Test
    @DisplayName("IT-OPS-12 ディスパッチャの手前で拒まれた要求は 400 で、本文がある")
    void rejectedByFirewallKeepsABody() throws Exception {
        var request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(
                        "http://localhost:%d/api/%%2e%%2e/me".formatted(port)))
                .GET().build();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(
                            java.nio.charset.StandardCharsets.UTF_8));

            assertThat(response.statusCode())
                    .as("403 なら ERROR 転送が塞がれている")
                    .isEqualTo(400);
            assertThat(response.body())
                    .as("本文が無いと、利用者も運用も原因に辿り着けない")
                    .isNotEmpty();
        }
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
