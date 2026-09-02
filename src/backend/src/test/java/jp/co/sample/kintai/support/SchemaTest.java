package jp.co.sample.kintai.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * マイグレーションが適用でき、設計書どおりのスキーマになっていることを確かめる。
 *
 * <p>ここが落ちるなら、他のすべての統合テストは意味を持たない。
 */
class SchemaTest extends IntegrationTestBase {

    @Test
    @DisplayName("IT-SCH-01 設計書のテーブルがすべて作られている")
    void allTablesExist() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                   AND table_name NOT LIKE 'flyway%'
                   AND table_name NOT LIKE 'time_clock_events_%'
                 ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "approval_events",
                "assignments",
                "company_calendars",
                "daily_attendance_slices",
                "daily_attendances",
                "departments",
                "employee_credentials",
                "employee_roles",
                "employees",
                "managerships",
                "monthly_attendances",
                "monthly_settlements",
                "time_clock_correction_items",
                "time_clock_correction_requests",
                "time_clock_events",
                "weekly_overtimes",
                "work_rule_assignments",
                "work_rule_series",
                "work_rules");
    }

    @Test
    @DisplayName("IT-SCH-02 btree_gist が有効になっている（EXCLUDE 制約の前提）")
    void btreeGistIsInstalled() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("IT-SCH-03 打刻テーブルが work_date でパーティション分割されている")
    void timeClockEventsIsPartitioned() {
        assertThat(jdbc.queryForObject("""
                SELECT partstrat FROM pg_partitioned_table
                 WHERE partrelid = 'time_clock_events'::regclass
                """, String.class)).isEqualTo("r");   // r = RANGE

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_inherits WHERE inhparent = 'time_clock_events'::regclass
                """, Integer.class)).isGreaterThanOrEqualTo(6);   // 2026-2030 + DEFAULT
    }

    @Test
    @DisplayName("IT-SCH-04 updated_at を持つ表すべてにトリガが張られている")
    void everyUpdatedAtHasTrigger() {
        List<String> withoutTrigger = jdbc.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'updated_at'
                 WHERE n.nspname = 'public' AND c.relkind = 'r' AND NOT a.attisdropped
                   AND NOT EXISTS (SELECT 1 FROM pg_trigger t
                                    WHERE t.tgrelid = c.oid AND NOT t.tgisinternal)
                 ORDER BY c.relname
                """, String.class);

        assertThat(withoutTrigger)
                .as("updated_at を持つのにトリガが無い表。DEFAULT now() は UPDATE で効かない")
                .isEmpty();
    }
}
