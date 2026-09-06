package jp.co.sample.kintai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
 * アクセスログと認証の記録（IT-OPS-08〜11）。
 *
 * <p><strong>ログが出ていることを確かめる。</strong>
 * 「設定した」だけでは、あとで書式を変えたときに黙って消える。
 * とくに認証の記録は、<strong>無くても業務は動く</strong>ので、
 * 消えたことに誰も気づかない。
 *
 * <p>本システムはログインのロックアウトを設けていないので、
 * 記録が総当たりに気づく唯一の手段である。
 */
@DisplayName("アクセスログと認証の記録")
class RequestLoggingTest extends WebIntegrationTestBase {

    @Autowired
    private EmployeeRepository employees;
    @Autowired
    private EmployeeCredentialRepository credentials;
    @Autowired
    private PasswordHasher hasher;

    private ListAppender<ILoggingEvent> access;
    private ListAppender<ILoggingEvent> audit;
    private EmployeeId taro;

    @BeforeEach
    void captureLogs() {
        access = attach("jp.co.sample.kintai.access");
        audit = attach("jp.co.sample.kintai.audit.signin");

        taro = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(taro, new EmployeeNumber("E0001"), "山田 太郎",
                new Email("e0001@example.com"), LocalDate.of(2026, 4, 1),
                Optional.empty(), Set.of(Role.EMPLOYEE)));
        credentials.save(new EmployeeCredential(taro,
                hasher.hash(new RawPassword("correct-horse-battery")),
                java.time.LocalDateTime.of(2026, 4, 1, 9, 0)));
    }

    @AfterEach
    void detachLogs() {
        detach("jp.co.sample.kintai.access", access);
        detach("jp.co.sample.kintai.audit.signin", audit);
    }

    /**
     * <strong>どの要求が失敗したかを外から追えるようにする。</strong>
     * 例外のスタックトレースだけでは、どの利用者のどの操作から出たのかが分からない。
     */
    @Test
    @DisplayName("IT-OPS-08 要求ごとに、方式・パス・状態・所要時間・利用者が残る")
    void accessLogCarriesTheRequest() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/attendances/current", taro.value())
                        .with(as(taro, "E0001", Role.EMPLOYEE)))
                .andExpect(status().isOk());

        assertThat(messagesOf(access))
                .anySatisfy(message -> assertThat(message)
                        .contains("GET")
                        .contains("/api/employees/" + taro.value() + "/attendances/current")
                        .contains("status=200")
                        .contains("elapsedMs=")
                        .contains("user=E0001"));
    }

    /**
     * <strong>死活監視は記録しない。</strong>
     * 15 秒ごとに叩かれるので、書くと 1 日 5,760 行がログの大半を占める。
     */
    @Test
    @DisplayName("IT-OPS-09 死活監視はアクセスログに残さない")
    void healthChecksAreNotLogged() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertThat(messagesOf(access))
                .noneSatisfy(message -> assertThat(message).contains("/actuator/health"));
    }

    /**
     * <strong>応答で理由を区別しないことと、記録しないことは別である。</strong>
     * 利用者には教えないが、運用する側は「誰が何回失敗しているか」を知る必要がある。
     */
    @Test
    @DisplayName("IT-OPS-10 ログインの失敗が理由つきで記録される")
    void failedSignInIsRecorded() throws Exception {
        signIn("E0001", "wrong-password").andExpect(status().isUnauthorized());
        signIn("E9999", "whatever-password").andExpect(status().isUnauthorized());

        assertThat(messagesOf(audit))
                .anySatisfy(m -> assertThat(m).contains("E0001").contains("BAD_PASSWORD"))
                .anySatisfy(m -> assertThat(m).contains("E9999")
                        .contains("NO_SUCH_EMPLOYEE"));
    }

    /**
     * <strong>パスワードを書かない。</strong>
     * ログは監査証跡ではないが、それでも平文の資格情報を置く場所ではない。
     */
    @Test
    @DisplayName("IT-OPS-11 ログにパスワードが出ない")
    void passwordsNeverReachTheLog() throws Exception {
        signIn("E0001", "correct-horse-battery").andExpect(status().isOk());
        signIn("E0001", "wrong-password").andExpect(status().isUnauthorized());

        assertThat(messagesOf(audit))
                .anySatisfy(m -> assertThat(m).contains("ログイン成功").contains("E0001"))
                .allSatisfy(m -> assertThat(m)
                        .doesNotContain("correct-horse-battery")
                        .doesNotContain("wrong-password"));
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String number,
                                                                     String password)
            throws Exception {
        return mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNumber\":\"%s\",\"password\":\"%s\"}"
                        .formatted(number, password)));
    }

    private static List<String> messagesOf(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ListAppender<ILoggingEvent> attach(String name) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(String name, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(name)).detachAppender(appender);
    }
}
