package jp.co.sample.kintai.attendance.infrastructure;

import static jp.co.sample.kintai.support.ConstraintAssertions.accepted;
import static jp.co.sample.kintai.support.ConstraintAssertions.rejectedBy;
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
 * 日次勤怠と内訳の制約（IT-ATT-02〜05・15〜19・22）。
 *
 * <p>対応する設計は
 * {@code doc/02_詳細設計/03_勤怠_打刻と日次集計/DB設計書.md} の 6 章。
 */
@DisplayName("日次勤怠の制約")
class DailyAttendanceConstraintTest extends IntegrationTestBase {

    private Fixtures fixtures;
    private UUID employee;
    private UUID workRule;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        employee = fixtures.employee("E0001", LocalDate.of(2026, 1, 1));
        workRule = fixtures.fixedWorkRule(
                fixtures.workRuleSeries("標準勤務"), LocalDate.of(2026, 1, 1));
    }

    /**
     * 日次勤怠を 1 行入れる。既定は正常な値。
     * ケースごとに変えたい 1 項目だけを上書きする（CLAUDE.md 落とし穴 12）。
     */
    private Daily daily() {
        return new Daily();
    }

    private final class Daily {
        LocalDate workDate = LocalDate.of(2026, 4, 7);
        String dayType = "WORKDAY";
        String system = "FIXED";
        int working = 780;
        int breakMinutes = 60;
        int base = 480;
        int overtimeWithin = 0;
        int overtimeBeyond = 300;
        int night = 300;
        int legalHoliday = 0;
        boolean breakSatisfied = true;

        UUID employeeId = employee;

        Daily employee(UUID v) { employeeId = v; return this; }
        Daily workDate(LocalDate v) { workDate = v; return this; }
        Daily dayType(String v) { dayType = v; return this; }
        Daily system(String v) { system = v; return this; }
        Daily working(int v) { working = v; return this; }
        Daily breakMinutes(int v) { breakMinutes = v; return this; }
        Daily base(int v) { base = v; return this; }
        Daily overtimeWithin(int v) { overtimeWithin = v; return this; }
        Daily overtimeBeyond(int v) { overtimeBeyond = v; return this; }
        Daily night(int v) { night = v; return this; }
        Daily legalHoliday(int v) { legalHoliday = v; return this; }
        Daily breakSatisfied(boolean v) { breakSatisfied = v; return this; }

        UUID insert() {
            UUID id = Fixtures.id();
            jdbc.update("""
                    INSERT INTO daily_attendances (id, employee_id, work_date, day_type,
                        working_time_system, work_rule_id, working_minutes, break_minutes,
                        base_minutes, overtime_within_statutory_minutes,
                        overtime_beyond_statutory_minutes, night_minutes,
                        legal_holiday_minutes, break_requirement_satisfied)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, employeeId, workDate, dayType, system, workRule, working,
                    breakMinutes, base, overtimeWithin, overtimeBeyond, night,
                    legalHoliday, breakSatisfied);
            return id;
        }
    }

    @Test
    @DisplayName("IT-ATT-19 正常な日次勤怠を登録できる")
    void normalRowIsAccepted() {
        accepted(() -> daily().insert());
    }

    @Nested
    @DisplayName("集計値の整合")
    class Totals {

        @Test
        @DisplayName("IT-ATT-02 内訳の合計が実労働時間と一致しないと拒否される")
        void breakdownMustSumToWorkingTime() {
            rejectedBy("daily_attendances_breakdown_check",
                    () -> daily().overtimeBeyond(200).insert());
        }

        @Test
        @DisplayName("IT-ATT-03 深夜労働が実労働時間を超えると拒否される")
        void nightCannotExceedWorking() {
            rejectedBy("daily_attendances_night_within_working_check",
                    () -> daily().night(800).insert());
        }

        /**
         * 深夜は他の区分に重ねて付く属性であり、合計には数えない。
         * 実労働 780 分に対して深夜 300 分・法定外残業 300 分が
         * <strong>完全に重なっている</strong>のが正しい状態である。
         */
        @Test
        @DisplayName("IT-ATT-17 深夜と法定外残業が完全に重なる行は受け入れられる")
        void nightOverlapsOvertime() {
            accepted(() -> daily().night(300).overtimeBeyond(300).insert());
        }
    }

    @Nested
    @DisplayName("労働時間制度")
    class WorkingTimeSystem {

        @Test
        @DisplayName("IT-ATT-04 フレックスに日次の法定外残業を計上すると拒否される")
        void flexCannotHaveDailyOvertime() {
            rejectedBy("daily_attendances_flex_check",
                    () -> daily().system("FLEX").working(600).base(480)
                            .overtimeBeyond(120).night(0).insert());
        }

        @Test
        @DisplayName("フレックスは全時間を基本時間として計上する")
        void flexPutsEverythingIntoBase() {
            accepted(() -> daily().system("FLEX").working(600).base(600)
                    .overtimeBeyond(0).night(0).insert());
        }
    }

    @Nested
    @DisplayName("休憩の充足（BR-08）")
    class BreakRequirement {

        @Test
        @DisplayName("IT-ATT-05 実労働 540 分・休憩 0 分で満たしていると主張すると拒否される")
        void inconsistentBreakRequirement() {
            rejectedBy("daily_attendances_break_requirement_check",
                    () -> daily().working(540).breakMinutes(0).base(480)
                            .overtimeBeyond(60).night(0).breakSatisfied(true).insert());
        }

        @Test
        @DisplayName("実労働 6 時間以下なら休憩が無くても満たしている")
        void noBreakNeededUnderSixHours() {
            accepted(() -> daily().working(360).breakMinutes(0).base(360)
                    .overtimeBeyond(0).night(0).breakSatisfied(true).insert());
        }
    }

    /**
     * 勤務日は土曜（所定休日）だが、日曜（法定休日）へまたいだ勤務。
     *
     * <p>第 1 版は {@code day_type = 'LEGAL_HOLIDAY' OR legal_holiday_minutes = 0} という
     * 制約を持っており、<strong>この正しい計算結果を保存できなかった。</strong>
     * 法定休日労働は暦日で判断するため、勤務日の区分で縛ってはいけない。
     */
    @Test
    @DisplayName("IT-ATT-18 所定休日の勤務日に法定休日労働を計上できる（日曜へまたいだ勤務）")
    void legalHolidayWorkOnANonLegalHolidayWorkDate() {
        accepted(() -> daily()
                .workDate(LocalDate.of(2026, 4, 4))
                .dayType("NON_LEGAL_HOLIDAY")
                .working(480).breakMinutes(60)
                .base(0).overtimeWithin(120).overtimeBeyond(0)
                .night(420).legalHoliday(360)
                .insert());
    }

    @Nested
    @DisplayName("内訳（slices）")
    class Slices {

        private UUID dailyId;

        @BeforeEach
        void setUpDaily() {
            dailyId = daily().insert();
        }

        private void slice(int sequenceNo, LocalDate calendarDate,
                           String from, String to, String... premiums) {
            jdbc.update("""
                    INSERT INTO daily_attendance_slices (id, daily_attendance_id, sequence_no,
                            calendar_date, started_at, ended_at, premiums)
                    VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::text[])
                    """, Fixtures.id(), dailyId, sequenceNo, calendarDate, from, to,
                    "{" + String.join(",", premiums) + "}");
        }

        @Test
        @DisplayName("IT-ATT-15 未知の割増区分は拒否される")
        void unknownPremium() {
            rejectedBy("daily_attendance_slices_premiums_check",
                    () -> slice(1, LocalDate.of(2026, 4, 7),
                            "2026-04-07 13:00:00+09", "2026-04-07 18:00:00+09",
                            "HOLIDAY_BONUS"));
        }

        @Test
        @DisplayName("IT-ATT-16 1 区間に排他的な区分を 2 つ付けると拒否される")
        void twoExclusivePremiumsInOneSlice() {
            rejectedBy("daily_attendance_slices_exclusive_premium_check",
                    () -> slice(1, LocalDate.of(2026, 4, 7),
                            "2026-04-07 13:00:00+09", "2026-04-07 18:00:00+09",
                            "OVERTIME_WITHIN_STATUTORY", "OVERTIME_BEYOND_STATUTORY"));
        }

        @Test
        @DisplayName("深夜は他の区分に重ねて付けられる")
        void nightCanOverlapAnotherPremium() {
            accepted(() -> slice(1, LocalDate.of(2026, 4, 8),
                    "2026-04-08 00:00:00+09", "2026-04-08 03:00:00+09",
                    "NIGHT", "OVERTIME_BEYOND_STATUTORY"));
        }

        @Test
        @DisplayName("区間は暦日境界で分かれ、calendar_date でどの暦日か分かる")
        void slicesDoNotCrossCalendarDays() {
            slice(1, LocalDate.of(2026, 4, 7),
                    "2026-04-07 22:00:00+09", "2026-04-08 00:00:00+09",
                    "NIGHT", "OVERTIME_BEYOND_STATUTORY");
            slice(2, LocalDate.of(2026, 4, 8),
                    "2026-04-08 00:00:00+09", "2026-04-08 03:00:00+09",
                    "NIGHT", "OVERTIME_BEYOND_STATUTORY");

            assertThat(jdbc.queryForList(
                    "SELECT calendar_date FROM daily_attendance_slices "
                            + "WHERE daily_attendance_id = ? ORDER BY sequence_no",
                    LocalDate.class, dailyId))
                    .containsExactly(LocalDate.of(2026, 4, 7), LocalDate.of(2026, 4, 8));
        }

        /**
         * <strong>レビューで見つかった穴。</strong>
         *
         * <p>上のテストは整合した 2 行を入れて読み戻すだけで、
         * <strong>自分が入れた値を確認していた</strong>（どの制約も検査していない）。
         * 実際に、無関係な {@code calendar_date}・暦日をまたぐ区間・重なった区間の
         * いずれも DB が受け入れていた。
         */
        @Test
        @DisplayName("IT-ATT-23 calendar_date が開始時刻の暦日と食い違うと拒否される")
        void calendarDateMustMatchTheStart() {
            rejectedBy("daily_attendance_slices_calendar_date_check",
                    () -> slice(1, LocalDate.of(2030, 12, 25),
                            "2026-04-07 13:00:00+09", "2026-04-07 18:00:00+09"));
        }

        @Test
        @DisplayName("IT-ATT-24 区間が暦日をまたぐと拒否される")
        void sliceMustStayInsideOneCalendarDay() {
            rejectedBy("daily_attendance_slices_single_day_check",
                    () -> slice(1, LocalDate.of(2026, 4, 7),
                            "2026-04-07 22:00:00+09", "2026-04-08 03:00:00+09", "NIGHT"));
        }

        /** 終了が翌日 0:00 ちょうどになるのは半開区間として正当。 */
        @Test
        @DisplayName("IT-ATT-25 終了が翌日 0:00 ちょうどの区間は受け入れる")
        void endingExactlyAtMidnightIsAccepted() {
            accepted(() -> slice(1, LocalDate.of(2026, 4, 7),
                    "2026-04-07 22:00:00+09", "2026-04-08 00:00:00+09", "NIGHT"));
        }

        @Test
        @DisplayName("IT-ATT-26 同じ日次勤怠の区間が重なると拒否される")
        void overlappingSlicesAreRejected() {
            slice(1, LocalDate.of(2026, 4, 7),
                    "2026-04-07 09:00:00+09", "2026-04-07 12:00:00+09");

            rejectedBy("daily_attendance_slices_no_overlap",
                    () -> slice(2, LocalDate.of(2026, 4, 7),
                            "2026-04-07 11:00:00+09", "2026-04-07 14:00:00+09"));
        }

        /** 接しているだけの区間は重ならない。半開区間なので正当。 */
        @Test
        @DisplayName("IT-ATT-27 接しているだけの区間は受け入れる")
        void touchingSlicesAreAccepted() {
            slice(1, LocalDate.of(2026, 4, 7),
                    "2026-04-07 09:00:00+09", "2026-04-07 12:00:00+09");

            accepted(() -> slice(2, LocalDate.of(2026, 4, 7),
                    "2026-04-07 12:00:00+09", "2026-04-07 14:00:00+09"));
        }

        /** 別の日次勤怠どうしなら、同じ時刻の区間があってよい（別の社員の勤務）。 */
        @Test
        @DisplayName("IT-ATT-28 別の日次勤怠なら同じ時刻の区間を持てる")
        void otherAttendancesMayShareTheSameInstant() {
            slice(1, LocalDate.of(2026, 4, 7),
                    "2026-04-07 09:00:00+09", "2026-04-07 12:00:00+09");
            UUID otherDaily = daily()
                    .employee(fixtures.employee("E0900", LocalDate.of(2026, 1, 1)))
                    .insert();

            accepted(() -> jdbc.update("""
                    INSERT INTO daily_attendance_slices (id, daily_attendance_id, sequence_no,
                            calendar_date, started_at, ended_at, premiums)
                    VALUES (?, ?, 1, DATE '2026-04-07',
                            '2026-04-07 09:00:00+09'::timestamptz,
                            '2026-04-07 12:00:00+09'::timestamptz, '{}'::text[])
                    """, Fixtures.id(), otherDaily));
        }
    }

    @Test
    @DisplayName("IT-ATT-22 再計算で calculated_at が更新される")
    void calculatedAtIsMaintained() {
        UUID id = daily().insert();
        assertThat(jdbc.queryForObject(
                "SELECT calculated_at = created_at FROM daily_attendances WHERE id = ?",
                Boolean.class, id)).isTrue();

        jdbc.update("UPDATE daily_attendances SET version = version + 1 WHERE id = ?", id);

        assertThat(jdbc.queryForObject(
                "SELECT calculated_at > created_at FROM daily_attendances WHERE id = ?",
                Boolean.class, id)).isTrue();
    }
}
