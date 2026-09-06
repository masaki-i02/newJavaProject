package jp.co.sample.kintai.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WebIntegrationTestBase;

/**
 * 死活監視の口（IT-OPS-01〜04）。
 *
 * <p><strong>「依存に入っている」と「開いている」は別である。</strong>
 * actuator は最初から依存にあり {@code management.endpoints} で公開もしていたが、
 * {@code anyRequest().denyAll()} が塞いでいたため、
 * <strong>コンテナの {@code HEALTHCHECK} は起動直後から永久に失敗していた。</strong>
 * 一度も叩かれたことのない口は、開いているように見えるだけだった
 * （CLAUDE.md 落とし穴 87）。
 *
 * <p>だからここで叩く。設定を消せばこのテストが落ちる。
 */
@DisplayName("死活監視の口")
class ActuatorAccessTest extends WebIntegrationTestBase {

    @Autowired
    private EmployeeRepository employees;

    /** コンテナやロードバランサは認証を持たない。未認証で 200 が返らなければ意味が無い。 */
    @Test
    @DisplayName("IT-OPS-01 未認証でも health は 200 を返す")
    void healthIsOpen() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * <strong>liveness と readiness を分ける。</strong>
     * 分けないと、Flyway がマイグレーションを流している最中にトラフィックが入る。
     */
    @Test
    @DisplayName("IT-OPS-02 liveness と readiness が別々に引ける")
    void probesAreSeparated() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * <strong>未認証には UP / DOWN だけを返す。</strong>
     * 依存の内訳（DB のバージョンや接続先）は監視に要らず、攻撃者には有用である。
     */
    @Test
    @DisplayName("IT-OPS-03 未認証の health は依存の内訳を出さない")
    void detailsAreHiddenFromAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /**
     * <strong>開けるのは health だけである。</strong>
     * {@code env} は設定値を、{@code beans} は依存の一覧を返す。
     * どちらも監視には要らず、認証済みの一般社員にも見せる理由が無い。
     */
    @Test
    @DisplayName("IT-OPS-04 env と beans は認証済みでも開かない")
    void otherEndpointsStayClosed() throws Exception {
        var taro = new EmployeeId(UUID.randomUUID());
        employees.save(new Employee(taro, new EmployeeNumber("E0001"), "山田 太郎",
                new Email("e0001@example.com"), LocalDate.of(2026, 4, 1),
                Optional.empty(), Set.of(Role.EMPLOYEE)));

        mockMvc.perform(get("/actuator/env").with(as(taro, "E0001", Role.EMPLOYEE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/beans").with(as(taro, "E0001", Role.EMPLOYEE)))
                .andExpect(status().isForbidden());
    }
}
