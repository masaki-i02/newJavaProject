package jp.co.sample.kintai.support;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// Spring Boot 4 は Jackson 3 を使う。ObjectMapper のパッケージが
// com.fasterxml.jackson.databind から tools.jackson.databind へ変わっている
import tools.jackson.databind.ObjectMapper;

/**
 * API を実際に呼ぶ統合テストの土台。
 *
 * <p><strong>{@code @WebMvcTest} にしない。</strong>
 * コントローラだけを切り出してリポジトリを差し替えると、
 * 「アダプタが番兵を写せていない」「タイムゾーンが往復で崩れる」といった
 * <em>層をまたいだ</em>欠陥を素通りさせる。API から DB までを通す。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
public abstract class WebIntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected DataSource dataSource;

    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @BeforeEach
    void setUpWeb() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE TABLE
                    approval_events,
                    time_clock_correction_items,
                    time_clock_correction_requests,
                    monthly_attendances,
                    weekly_overtimes,
                    monthly_settlements,
                    daily_attendance_slices,
                    daily_attendances,
                    time_clock_events,
                    work_rule_assignments,
                    work_rules,
                    work_rule_series,
                    company_calendars,
                    managerships,
                    assignments,
                    employee_roles,
                    employee_credentials,
                    employees
                RESTART IDENTITY CASCADE
                """);
    }
}
