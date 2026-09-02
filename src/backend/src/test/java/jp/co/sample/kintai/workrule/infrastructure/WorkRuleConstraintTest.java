package jp.co.sample.kintai.workrule.infrastructure;

import static jp.co.sample.kintai.support.ConstraintAssertions.accepted;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedBy;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedWithMessage;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Fixtures;
import jp.co.sample.kintai.support.IntegrationTestBase;

/**
 * 就業規則・カレンダーの制約（IT-WR-01〜22）。
 *
 * <p>対応する設計は
 * {@code doc/02_詳細設計/02_就業規則・カレンダー/DB設計書.md} の 6 章。
 */
@DisplayName("就業規則・カレンダーの制約")
class WorkRuleConstraintTest extends IntegrationTestBase {

    private Fixtures fixtures;
    private UUID standardSeries;
    private UUID flexSeries;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        standardSeries = fixtures.workRuleSeries("標準勤務");
        flexSeries = fixtures.workRuleSeries("フレックス勤務");
    }

    @Nested
    @DisplayName("労働時間制度ごとの列の充足")
    class Variant {

        @Test
        @DisplayName("IT-WR-01 FLEX なのに始業時刻を設定すると拒否される")
        void flexWithScheduledStart() {
            rejectedBy("work_rules_variant_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        flexible_start, flexible_end, core_start, core_end,
                        standard_daily_minutes, scheduled_start)
                    VALUES (?, ?, 'FLEX', DATE '2026-04-01', TIME '07:00', TIME '22:00',
                            TIME '11:00', TIME '15:00', 480, TIME '09:00')
                    """, Fixtures.id(), flexSeries));
        }

        @Test
        @DisplayName("IT-WR-02 FIXED なのにコアタイムを設定すると拒否される")
        void fixedWithCoreTime() {
            rejectedBy("work_rules_variant_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes, core_start)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', TIME '09:00', TIME '18:00',
                            60, TIME '11:00')
                    """, Fixtures.id(), standardSeries));
        }

        @Test
        @DisplayName("IT-WR-03 FLEX でコアタイムの開始を欠くと拒否される")
        void flexWithoutCoreStart() {
            rejectedBy("work_rules_variant_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        flexible_start, flexible_end, core_end, standard_daily_minutes)
                    VALUES (?, ?, 'FLEX', DATE '2026-04-01', TIME '07:00', TIME '22:00',
                            TIME '15:00', 480)
                    """, Fixtures.id(), flexSeries));
        }

        @Test
        @DisplayName("IT-WR-04 コアタイムがフレキシブルタイムの外にあると拒否される")
        void coreOutsideFlexible() {
            rejectedBy("work_rules_core_within_flexible_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        flexible_start, flexible_end, core_start, core_end, standard_daily_minutes)
                    VALUES (?, ?, 'FLEX', DATE '2026-04-01', TIME '07:00', TIME '22:00',
                            TIME '06:00', TIME '15:00', 480)
                    """, Fixtures.id(), flexSeries));
        }
    }

    @Nested
    @DisplayName("法定値の範囲")
    class StatutoryLimits {

        @Test
        @DisplayName("IT-WR-05 法定外残業の割増率を 0.100 にすると拒否される")
        void premiumRateBelowLegalMinimum() {
            rejectedBy("work_rules_rate_overtime_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes, rate_overtime)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', TIME '09:00', TIME '18:00', 60, 0.100)
                    """, Fixtures.id(), standardSeries));
        }

        @Test
        @DisplayName("IT-WR-06 1 日の法定労働時間を 12 時間にすると拒否される")
        void statutoryDailyAboveLegalMaximum() {
            rejectedBy("work_rules_statutory_daily_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes,
                        statutory_daily_minutes)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', TIME '09:00', TIME '18:00', 60, 720)
                    """, Fixtures.id(), standardSeries));
        }

        @Test
        @DisplayName("IT-WR-07 深夜帯を 02:00-03:00 にすると拒否される")
        void arbitraryNightWindow() {
            rejectedBy("work_rules_night_window_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes,
                        night_start, night_end)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', TIME '09:00', TIME '18:00', 60,
                            TIME '02:00', TIME '03:00')
                    """, Fixtures.id(), standardSeries));
        }

        @Test
        @DisplayName("IT-WR-08 所定 9 時間（09:00-19:00 / 休憩 60 分）の規則は拒否される")
        void scheduledExceedsStatutory() {
            rejectedBy("work_rules_scheduled_within_statutory_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', TIME '09:00', TIME '19:00', 60)
                    """, Fixtures.id(), standardSeries));
        }

        @Test
        @DisplayName("IT-WR-09 所定 7 時間（09:00-16:30）なのに休憩 30 分だと拒否される")
        void breakBelowLegalMinimum() {
            rejectedBy("work_rules_break_statutory_check", () -> jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', TIME '09:00', TIME '16:30', 30)
                    """, Fixtures.id(), standardSeries));
        }
    }

    @Nested
    @DisplayName("系列と版")
    class SeriesAndRevisions {

        @Test
        @DisplayName("IT-WR-10 同じ系列に期間の重なる版を作ると拒否される")
        void overlappingRevisionsInSameSeries() {
            fixtures.fixedWorkRule(standardSeries, LocalDate.of(2026, 4, 1));
            rejectedBy("work_rules_no_overlapping_versions",
                    () -> fixtures.fixedWorkRule(standardSeries, LocalDate.of(2026, 6, 1)));
        }

        @Test
        @DisplayName("IT-WR-11 別系列なら同じ期間の版を作れる")
        void sameePeriodInDifferentSeries() {
            fixtures.fixedWorkRule(standardSeries, LocalDate.of(2026, 4, 1));
            accepted(() -> fixtures.flexWorkRule(flexSeries, LocalDate.of(2026, 4, 1)));
        }

        @Test
        @DisplayName("IT-WR-18 日をまたぐ固定勤務（22:00-06:00 / 休憩 60 分）の所定は 420 分になる")
        void overnightScheduleIsSevenHours() {
            UUID nightSeries = fixtures.workRuleSeries("夜勤");
            UUID id = Fixtures.id();
            jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', TIME '22:00', TIME '06:00', 60)
                    """, id, nightSeries);

            assertThat(jdbc.queryForObject(
                    "SELECT scheduled_working_minutes FROM work_rules WHERE id = ?",
                    Integer.class, id)).isEqualTo(420);
        }

        @Test
        @DisplayName("IT-WR-19 改定しても適用は切れず、日付に応じた版が引ける")
        void assignmentSurvivesRevision() {
            // 標準勤務を 2026-10-01 に改定する。適用行には一切触れない
            UUID before = Fixtures.id();
            jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from, valid_to,
                        scheduled_start, scheduled_end, scheduled_break_minutes)
                    VALUES (?, ?, 'FIXED', DATE '2026-04-01', DATE '2026-10-01',
                            TIME '09:00', TIME '18:00', 60)
                    """, before, standardSeries);
            UUID after = Fixtures.id();
            jdbc.update("""
                    INSERT INTO work_rules (id, series_id, working_time_system, valid_from,
                        scheduled_start, scheduled_end, scheduled_break_minutes)
                    VALUES (?, ?, 'FIXED', DATE '2026-10-01', TIME '09:00', TIME '17:30', 60)
                    """, after, standardSeries);

            UUID employee = fixtures.employee("E0001", LocalDate.of(2026, 4, 1));
            fixtures.assignWorkRule(employee, standardSeries, LocalDate.of(2026, 4, 1));

            assertThat(effectiveRuleOn(employee, LocalDate.of(2026, 5, 5))).isEqualTo(before);
            assertThat(effectiveRuleOn(employee, LocalDate.of(2026, 11, 5))).isEqualTo(after);
        }

        /** DB設計書 4.1 のクエリ。適用（系列）を先に決め、そのうえで有効な版を選ぶ。 */
        private UUID effectiveRuleOn(UUID employeeId, LocalDate date) {
            return jdbc.queryForObject("""
                    SELECT r.id
                      FROM work_rule_assignments a
                      JOIN work_rule_series s ON s.id = a.work_rule_series_id
                      JOIN work_rules r       ON r.series_id = s.id
                     WHERE a.employee_id = ?
                       AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to > ?)
                       AND r.valid_from <= ? AND (r.valid_to IS NULL OR r.valid_to > ?)
                    """, UUID.class, employeeId, date, date, date, date);
        }
    }

    @Nested
    @DisplayName("社員への適用")
    class Assignment {

        @Test
        @DisplayName("IT-WR-12 同一社員に期間の重なる適用を登録すると拒否される")
        void overlappingAssignments() {
            UUID employee = fixtures.employee("E0001", LocalDate.of(2026, 4, 1));
            fixtures.assignWorkRule(employee, standardSeries, LocalDate.of(2026, 4, 1));
            rejectedBy("work_rule_assignments_no_overlap",
                    () -> fixtures.assignWorkRule(employee, flexSeries, LocalDate.of(2026, 7, 1)));
        }

        @Test
        @DisplayName("IT-WR-13 月初日でも入社日でもない日から適用すると拒否される")
        void assignmentMustStartAtMonthOrHireDate() {
            UUID employee = fixtures.employee("E0002", LocalDate.of(2026, 4, 15));
            rejectedWithMessage("月初日か入社日に限ります",
                    () -> fixtures.assignWorkRule(employee, standardSeries,
                            LocalDate.of(2026, 5, 10)));
        }

        @Test
        @DisplayName("IT-WR-14 月中入社の社員に、入社日から適用できる")
        void assignmentCanStartAtHireDate() {
            UUID employee = fixtures.employee("E0002", LocalDate.of(2026, 4, 15));
            accepted(() -> fixtures.assignWorkRule(employee, standardSeries,
                    LocalDate.of(2026, 4, 15)));
        }

        @Test
        @DisplayName("IT-WR-15 入社日より前から適用すると拒否される")
        void assignmentBeforeHireDate() {
            UUID employee = fixtures.employee("E0004", LocalDate.of(2026, 4, 1));
            rejectedWithMessage("入社日より前に就業規則は適用できません",
                    () -> fixtures.assignWorkRule(employee, standardSeries,
                            LocalDate.of(2026, 3, 1)));
        }

        @Test
        @DisplayName("IT-WR-16 退職済みの社員に適用すると拒否される")
        void assignmentToRetiredEmployee() {
            UUID retired = fixtures.employee("E0003", LocalDate.of(2020, 4, 1),
                    LocalDate.of(2026, 3, 31));
            rejectedWithMessage("退職済みの社員に就業規則は適用できません",
                    () -> fixtures.assignWorkRule(retired, standardSeries,
                            LocalDate.of(2026, 6, 1)));
        }
    }

    @Nested
    @DisplayName("会社カレンダー")
    class Calendar {

        @Test
        @DisplayName("IT-WR-17 未定義の暦日区分を登録すると拒否される")
        void unknownDayType() {
            rejectedBy("company_calendars_day_type_check", () -> jdbc.update(
                    "INSERT INTO company_calendars (calendar_date, day_type) VALUES (?, ?)",
                    LocalDate.of(2026, 6, 1), "HOLIDAY"));
        }

        @Test
        @DisplayName("IT-WR-20 所定労働日数のクエリが、未登録の日を所定労働日として数える")
        void unregisteredDaysCountAsWorkdays() {
            // 2026-06 の土日だけを休日として登録する。祝日は無い
            jdbc.update("""
                    INSERT INTO company_calendars (calendar_date, day_type, name)
                    SELECT d::date,
                           CASE WHEN extract(dow from d) = 0
                                THEN 'LEGAL_HOLIDAY' ELSE 'NON_LEGAL_HOLIDAY' END,
                           CASE WHEN extract(dow from d) = 0 THEN '法定休日' ELSE '所定休日' END
                      FROM generate_series(DATE '2026-06-01', DATE '2026-06-30', INTERVAL '1 day') d
                     WHERE extract(dow from d) IN (0, 6)
                    """);

            assertThat(workdayCount(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)))
                    .isEqualTo(22);
        }

        @Test
        @DisplayName("IT-WR-21 2026-06 は所定総労働時間が法定総枠を超えることを検出できる")
        void detectsScheduleExceedingStatutoryLimit() {
            jdbc.update("""
                    INSERT INTO company_calendars (calendar_date, day_type, name)
                    SELECT d::date,
                           CASE WHEN extract(dow from d) = 0
                                THEN 'LEGAL_HOLIDAY' ELSE 'NON_LEGAL_HOLIDAY' END,
                           '休日'
                      FROM generate_series(DATE '2026-06-01', DATE '2026-06-30', INTERVAL '1 day') d
                     WHERE extract(dow from d) IN (0, 6)
                    """);
            int workdays = workdayCount(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

            int scheduledTotal = workdays * 480;                 // 22 日 × 8 時間
            int statutoryLimit = 30 * 2400 / 7;                  // 暦日 30 日 ÷ 7 × 40 時間

            assertThat(scheduledTotal).isEqualTo(10_560);
            assertThat(statutoryLimit).isEqualTo(10_285);
            assertThat(scheduledTotal)
                    .as("所定どおり働くだけで法定外残業が 275 分発生する月がある")
                    .isGreaterThan(statutoryLimit);
        }

        /** DB設計書 4.2 のクエリ。登録が無い日は所定労働日として数える。 */
        private int workdayCount(LocalDate from, LocalDate toExclusive) {
            return jdbc.queryForObject("""
                    SELECT count(*)
                      FROM generate_series(?::date, ?::date - 1, INTERVAL '1 day')
                               AS d(calendar_date)
                      LEFT JOIN company_calendars c ON c.calendar_date = d.calendar_date::date
                     WHERE coalesce(c.day_type, 'WORKDAY') = 'WORKDAY'
                    """, Integer.class, from, toExclusive);
        }
    }

    @Test
    @DisplayName("IT-WR-22 updated_at が UPDATE で更新される")
    void updatedAtIsMaintained() {
        assertThat(jdbc.queryForObject(
                "SELECT updated_at = created_at FROM work_rule_series WHERE id = ?",
                Boolean.class, standardSeries)).isTrue();

        jdbc.update("UPDATE work_rule_series SET name = ? WHERE id = ?",
                "標準勤務（改称）", standardSeries);

        assertThat(jdbc.queryForObject(
                "SELECT updated_at > created_at FROM work_rule_series WHERE id = ?",
                Boolean.class, standardSeries)).isTrue();
    }
}
