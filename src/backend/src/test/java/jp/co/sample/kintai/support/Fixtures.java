package jp.co.sample.kintai.support;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 制約テスト用のデータを組み立てる。
 *
 * <p><strong>既定値は必ず「正常な値」にする。</strong>
 * ケースごとに変えたい 1 項目だけを上書きすれば、
 * 「入力を 1 つだけ変える」（CLAUDE.md 落とし穴 12）が自然に守られる。
 */
public final class Fixtures {

    private final JdbcTemplate jdbc;

    public Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static UUID id() {
        return UUID.randomUUID();
    }

    // --- 社員 ------------------------------------------------------------

    public UUID employee(String number, LocalDate hiredOn) {
        return employee(number, hiredOn, null);
    }

    public UUID employee(String number, LocalDate hiredOn, LocalDate retiredOn) {
        UUID id = id();
        jdbc.update("""
                INSERT INTO employees (id, employee_number, name, email, hired_on, retired_on)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, number, number + " 太郎", number.toLowerCase() + "@example.com",
                hiredOn, retiredOn);
        return id;
    }

    public void grantRole(UUID employeeId, String role) {
        jdbc.update("INSERT INTO employee_roles (employee_id, role) VALUES (?, ?)",
                employeeId, role);
    }

    // --- 組織 ------------------------------------------------------------

    public UUID department(String code, String name) {
        return department(code, name, null);
    }

    public UUID department(String code, String name, UUID parentId) {
        UUID id = id();
        jdbc.update("INSERT INTO departments (id, code, name, parent_id) VALUES (?, ?, ?, ?)",
                id, code, name, parentId);
        return id;
    }

    public UUID assign(UUID employeeId, UUID departmentId, LocalDate validFrom) {
        return assign(employeeId, departmentId, validFrom, null);
    }

    public UUID assign(UUID employeeId, UUID departmentId,
                       LocalDate validFrom, LocalDate validTo) {
        UUID id = id();
        jdbc.update("""
                INSERT INTO assignments (id, employee_id, department_id, valid_from, valid_to)
                VALUES (?, ?, ?, ?, ?)
                """, id, employeeId, departmentId, validFrom, validTo);
        return id;
    }

    public UUID appointManager(UUID departmentId, UUID employeeId, LocalDate validFrom) {
        return appointManager(departmentId, employeeId, validFrom, null);
    }

    public UUID appointManager(UUID departmentId, UUID employeeId,
                               LocalDate validFrom, LocalDate validTo) {
        UUID id = id();
        jdbc.update("""
                INSERT INTO managerships (id, department_id, employee_id, valid_from, valid_to)
                VALUES (?, ?, ?, ?, ?)
                """, id, departmentId, employeeId, validFrom, validTo);
        return id;
    }

    // --- 就業規則 --------------------------------------------------------

    public UUID workRuleSeries(String name) {
        UUID id = id();
        jdbc.update("INSERT INTO work_rule_series (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    /** 正常な固定時間制の版。9:00-18:00 / 休憩 60 分 = 所定 8 時間。 */
    public UUID fixedWorkRule(UUID seriesId, LocalDate validFrom) {
        UUID id = id();
        jdbc.update("""
                INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                                        scheduled_start, scheduled_end, scheduled_break_minutes)
                VALUES (?, ?, 'FIXED', ?, TIME '09:00', TIME '18:00', 60)
                """, id, seriesId, validFrom);
        return id;
    }

    /** 正常なフレックスの版。フレキシブル 07:00-22:00 / コア 11:00-15:00 / 1 日 8 時間。 */
    public UUID flexWorkRule(UUID seriesId, LocalDate validFrom) {
        UUID id = id();
        jdbc.update("""
                INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                                        flexible_start, flexible_end, core_start, core_end,
                                        standard_daily_minutes)
                VALUES (?, ?, 'FLEX', ?, TIME '07:00', TIME '22:00',
                        TIME '11:00', TIME '15:00', 480)
                """, id, seriesId, validFrom);
        return id;
    }

    public UUID assignWorkRule(UUID employeeId, UUID seriesId, LocalDate validFrom) {
        UUID id = id();
        jdbc.update("""
                INSERT INTO work_rule_assignments (id, employee_id, work_rule_series_id, valid_from)
                VALUES (?, ?, ?, ?)
                """, id, employeeId, seriesId, validFrom);
        return id;
    }
}
