package jp.co.sample.kintai.support;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 実物の PostgreSQL に対して行う統合テストの土台。
 *
 * <p>Flyway のマイグレーションを適用した状態から始まる。
 * スキーマは {@code doc/02_詳細設計/**\/DB設計書.md} が正であり、
 * マイグレーションはそこから生成されている。
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    @Autowired
    protected DataSource dataSource;

    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @BeforeEach
    void setUpJdbc() {
        jdbc = new JdbcTemplate(dataSource);
        truncateAll();
    }

    /**
     * 全テーブルを空にする。
     *
     * <p>打刻は追記専用で {@code DELETE} しない方針だが、
     * <strong>テストでは方針の例外として消す。</strong>
     * ロールバックでは {@code DEFERRABLE} な制約トリガの検証ができないため。
     */
    protected void truncateAll() {
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
                    employee_credentials,
                    employee_roles,
                    managerships,
                    assignments,
                    departments,
                    employees
                RESTART IDENTITY CASCADE
                """);
    }
}
