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
 * 月次清算の制約（IT-SET-01〜27）。
 *
 * <p>対応する設計は {@code doc/02_詳細設計/04_勤怠_月次清算/DB設計書.md} の 6 章。
 */
@DisplayName("月次清算の制約")
class MonthlySettlementConstraintTest extends IntegrationTestBase {

    private Fixtures fixtures;
    private UUID employee;
    private UUID series;

    @BeforeEach
    void setUp() {
        fixtures = new Fixtures(jdbc);
        employee = fixtures.employee("E0001", LocalDate.of(2026, 1, 1));
        series = fixtures.workRuleSeries("標準勤務");
    }

    private Settlement settlement() {
        return new Settlement();
    }

    /** 既定は 2026-05（暦日 31 日）の正常な FIXED。総枠 = 31 × 2400 ÷ 7 = 10,628。 */
    private final class Settlement {
        LocalDate month = LocalDate.of(2026, 5, 1);
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate toExclusive = LocalDate.of(2026, 6, 1);
        String system = "FIXED";
        int working = 10_000;
        int legalHoliday = 0;
        int target = 10_000;
        int scheduledTotal = 9_600;
        int statutoryLimit = 10_628;
        int dailyOvertime = 300;
        int weeklyOvertime = 100;
        int carriedOverOvertime = 0;
        int overtime = 400;
        int shortage = 0;
        int night = 200;
        int coreAbsence = 0;
        int annualBefore = 0;
        boolean exceedsMonthly = false;
        boolean exceedsAnnual = false;
        boolean exceedsCombined = false;

        Settlement month(LocalDate v) { month = v; return this; }
        Settlement period(LocalDate f, LocalDate t) { from = f; toExclusive = t; return this; }
        Settlement system(String v) { system = v; return this; }
        Settlement working(int v) { working = v; return this; }
        Settlement legalHoliday(int v) { legalHoliday = v; return this; }
        Settlement target(int v) { target = v; return this; }
        Settlement scheduledTotal(int v) { scheduledTotal = v; return this; }
        Settlement statutoryLimit(int v) { statutoryLimit = v; return this; }
        Settlement dailyOvertime(int v) { dailyOvertime = v; return this; }
        Settlement weeklyOvertime(int v) { weeklyOvertime = v; return this; }
        Settlement carriedOverOvertime(int v) { carriedOverOvertime = v; return this; }
        Settlement overtime(int v) { overtime = v; return this; }
        Settlement shortage(int v) { shortage = v; return this; }
        Settlement annualBefore(int v) { annualBefore = v; return this; }
        Settlement exceedsMonthly(boolean v) { exceedsMonthly = v; return this; }
        Settlement exceedsAnnual(boolean v) { exceedsAnnual = v; return this; }
        Settlement exceedsCombined(boolean v) { exceedsCombined = v; return this; }

        /** フレックスの正常形へ切り替える。日次・週次の残業は 0 になる。 */
        Settlement flex() {
            system = "FLEX";
            dailyOvertime = 0;
            weeklyOvertime = 0;
            carriedOverOvertime = 0;
            night = 0;
            overtime = Math.max(0, target - statutoryLimit);
            shortage = Math.max(0, scheduledTotal - target);
            return this;
        }

        UUID insert() {
            UUID id = Fixtures.id();
            jdbc.update("""
                    INSERT INTO monthly_settlements (id, employee_id, target_month,
                        period_from, period_to_exclusive, work_rule_series_id,
                        working_time_system, working_minutes, legal_holiday_minutes,
                        target_working_minutes, scheduled_total_minutes,
                        statutory_total_limit_minutes, daily_overtime_minutes,
                        weekly_overtime_minutes, carried_over_overtime_minutes,
                        overtime_minutes, shortage_minutes,
                        night_minutes, core_time_absence_minutes,
                        annual_agreement_subject_before_minutes,
                        exceeds_monthly_agreement_limit, exceeds_annual_agreement_limit,
                        exceeds_combined_single_month_limit, calculated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, now())
                    """, id, employee, month, from, toExclusive, series, system, working,
                    legalHoliday, target, scheduledTotal, statutoryLimit, dailyOvertime,
                    weeklyOvertime, carriedOverOvertime, overtime, shortage, night,
                    coreAbsence, annualBefore,
                    exceedsMonthly, exceedsAnnual, exceedsCombined);
            return id;
        }
    }

    @Nested
    @DisplayName("正常系")
    class Normal {

        @Test
        @DisplayName("IT-SET-12 正常な FIXED の清算結果を登録できる")
        void fixedIsAccepted() {
            accepted(() -> settlement().insert());
        }

        @Test
        @DisplayName("IT-SET-13 正常な FLEX（所定 < 総枠）の清算結果を登録できる")
        void flexIsAccepted() {
            accepted(() -> settlement().target(10_700).working(10_700).flex().insert());
        }

        /**
         * <strong>この回のレビューで最も重要な検証。</strong>
         *
         * <p>2026-06 は暦日 30 日・所定労働日 22 日で、
         * 所定総 10,560 分 &gt; 法定総枠 10,285 分になる。
         * 対象労働時間が両者の間（10,400 分）に落ちると、
         * 時間外 115 分と不足 160 分が<strong>同時に正になる。</strong>
         *
         * <p>第 1 版は「時間外と不足は同時に発生しない」を無条件の制約にしており、
         * この適法な月を保存できなかった。
         */
        @Test
        @DisplayName("IT-SET-14 所定総 > 総枠 の月では、時間外と不足が同時に正でも登録できる")
        void overtimeAndShortageCanCoexistWhenScheduleExceedsLimit() {
            accepted(() -> settlement()
                    .month(LocalDate.of(2026, 6, 1))
                    .period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1))
                    .working(10_400).target(10_400)
                    .scheduledTotal(10_560).statutoryLimit(10_285)
                    .flex()
                    .insert());

            assertThat(jdbc.queryForObject(
                    "SELECT overtime_minutes FROM monthly_settlements", Integer.class))
                    .isEqualTo(115);
            assertThat(jdbc.queryForObject(
                    "SELECT shortage_minutes FROM monthly_settlements", Integer.class))
                    .isEqualTo(160);
        }

        /**
         * <strong>この形は算出式に反するので、そもそも作れない。</strong>
         * フレックスの時間外は {@code max(0, 対象労働 − 総枠)}、
         * 不足は {@code max(0, 所定総 − 対象労働)} と {@code variant_check} が決めている。
         * 両方が正なら {@code 所定総 > 対象労働 > 総枠} が導けるので、
         * 所定総 ≤ 総枠 の月に両方が正の行を作ろうとすると算出式の方に捕まる。
         *
         * <p>第 2 版まではこれを別の {@code overtime_shortage_check} でも守っていたが、
         * <strong>破れる行が 1 つも存在しない検査</strong>だったので外した
         * （CLAUDE.md 落とし穴 16）。
         * <strong>どの制約で拒否されたかを確かめる</strong>のが要点である（落とし穴 17・25）。
         */
        @Test
        @DisplayName("IT-SET-07 FLEX・所定総 ≤ 総枠 の月で時間外と不足が同時に正だと拒否される")
        void overtimeAndShortageCannotCoexistOtherwise() {
            rejectedBy("monthly_settlements_variant_check", () -> settlement()
                    .working(9_000).target(9_000)
                    .scheduledTotal(9_600).statutoryLimit(10_628)
                    .system("FLEX").dailyOvertime(0).weeklyOvertime(0)
                    .overtime(100).shortage(600)
                    .insert());
        }
    }

    @Nested
    @DisplayName("清算期間と総枠")
    class Period {

        @Test
        @DisplayName("IT-SET-01 対象月が月初日でないと拒否される")
        void targetMonthMustBeFirstDay() {
            rejectedBy("monthly_settlements_month_check", () -> settlement()
                    .month(LocalDate.of(2026, 5, 15)).insert());
        }

        @Test
        @DisplayName("IT-SET-15 清算期間が対象月の外へはみ出すと拒否される")
        void periodMustStayInsideTargetMonth() {
            rejectedBy("monthly_settlements_period_check", () -> settlement()
                    .period(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 15))
                    .statutoryLimit(45 * 2400 / 7).insert());
        }

        /**
         * 暦月で計算すると総枠が 10,285 分になり、
         * <strong>時間外労働が計上されずに賃金が不足する。</strong>
         */
        @Test
        @DisplayName("IT-SET-16 月中入社（4/15〜5/1・16 日）の総枠は 5,485 分になる")
        void midMonthHireShortensTheLimit() {
            accepted(() -> settlement()
                    .month(LocalDate.of(2026, 4, 1))
                    .period(LocalDate.of(2026, 4, 15), LocalDate.of(2026, 5, 1))
                    .working(5_000).target(5_000)
                    .scheduledTotal(5_280).statutoryLimit(16 * 2400 / 7)
                    .flex()
                    .insert());

            assertThat(jdbc.queryForObject(
                    "SELECT statutory_total_limit_minutes FROM monthly_settlements",
                    Integer.class)).isEqualTo(5_485);
        }

        @Test
        @DisplayName("IT-SET-17 総枠が清算期間の暦日数と一致しないと拒否される")
        void limitMustMatchThePeriod() {
            rejectedBy("monthly_settlements_statutory_limit_check",
                    () -> settlement().statutoryLimit(9_999).insert());
        }
    }

    @Nested
    @DisplayName("制度ごとの算出式")
    class Variant {

        @Test
        @DisplayName("IT-SET-02 対象労働時間が 実労働 − 法定休日 と一致しないと拒否される")
        void targetWorkingIsDerived() {
            rejectedBy("monthly_settlements_target_working_check",
                    () -> settlement().legalHoliday(300).insert());
        }

        @Test
        @DisplayName("IT-SET-03 FIXED で時間外が 日次 + 週次 + 通算 と一致しないと拒否される")
        void fixedOvertimeIsTheSumOfDailyAndWeekly() {
            rejectedBy("monthly_settlements_variant_check",
                    () -> settlement().overtime(500).insert());
        }

        /**
         * <strong>固定時間制でも不足時間は生じる。</strong>
         * 第 1 版は {@code FIXED AND shortage_minutes = 0} を制約にしており、
         * <strong>欠勤のある月をひとつも保存できなかった</strong>
         * （CLAUDE.md 落とし穴 23・51）。
         */
        @Test
        @DisplayName("IT-SET-04 FIXED に不足時間があっても登録できる（欠勤のある月）")
        void fixedCanHaveShortage() {
            accepted(() -> settlement()
                    .dailyOvertime(0).weeklyOvertime(0).overtime(0)
                    .working(9_000).target(9_000).shortage(600)
                    .insert());
        }

        @Test
        @DisplayName("IT-SET-21 FLEX に通算分の法定外残業を設定すると拒否される")
        void flexHasNoCarriedOverOvertime() {
            rejectedBy("monthly_settlements_variant_check", () -> settlement()
                    .target(10_700).working(10_700).flex()
                    .carriedOverOvertime(360).insert());
        }

        /**
         * <strong>制度が違えば正当な月である。</strong>
         * 固定時間制の時間外は日次・週次で確定した実績で、総枠との比較では求めていない。
         * 忙しい週に残業し、別の週に欠勤しただけの月がこれにあたる。
         */
        @Test
        @DisplayName("IT-SET-22 FIXED なら所定総 ≤ 総枠 の月でも時間外と不足が同時に正で登録できる")
        void fixedCanHaveBothOvertimeAndShortage() {
            accepted(() -> settlement()
                    .working(3_240).target(3_240)
                    .scheduledTotal(10_080).statutoryLimit(10_628)
                    .dailyOvertime(360).weeklyOvertime(480).overtime(840)
                    .shortage(6_840)
                    .insert());
        }

        /**
         * 通算分（BR-07）を含む時間外。
         * <strong>合計だけでなく由来ごとの内訳を持つ</strong>ので、
         * 3 つを足したものが合計であることを制約が守る。
         */
        @Test
        @DisplayName("IT-SET-23 FIXED の時間外は 日次 + 週次 + 通算 で登録できる")
        void fixedOvertimeIncludesCarriedOver() {
            UUID id = settlement().carriedOverOvertime(360).overtime(760).insert();

            assertThat(jdbc.queryForObject("""
                    SELECT daily_overtime_minutes + weekly_overtime_minutes
                           + carried_over_overtime_minutes
                    FROM monthly_settlements WHERE id = ?
                    """, Integer.class, id)).isEqualTo(760);
        }

        @Test
        @DisplayName("IT-SET-05 FLEX に日次の残業を設定すると拒否される")
        void flexHasNoDailyOvertime() {
            rejectedBy("monthly_settlements_variant_check", () -> settlement()
                    .target(10_700).working(10_700).flex().dailyOvertime(100).insert());
        }

        @Test
        @DisplayName("IT-SET-06 FLEX で時間外が総枠の超過分と一致しないと拒否される")
        void flexOvertimeIsDerivedFromTheLimit() {
            rejectedBy("monthly_settlements_variant_check", () -> settlement()
                    .target(10_700).working(10_700).flex().overtime(999).insert());
        }

        @Test
        @DisplayName("IT-SET-08 深夜労働が実労働時間を超えると拒否される")
        void nightCannotExceedWorking() {
            rejectedBy("monthly_settlements_night_check", () -> {
                Settlement s = settlement();
                s.night = 99_999;
                s.insert();
            });
        }
    }

    @Nested
    @DisplayName("36 協定の判定（BR-12）")
    class Agreement {

        @Test
        @DisplayName("IT-SET-18 限度時間の月次上限を超えているのに false だと拒否される")
        void monthlyFlagIsDerived() {
            rejectedBy("monthly_settlements_monthly_agreement_check", () -> settlement()
                    .working(13_000).target(13_000)
                    .dailyOvertime(2_760).weeklyOvertime(0).overtime(2_760)
                    .exceedsMonthly(false)
                    .insert());
        }

        @Test
        @DisplayName("IT-SET-19 年次上限を超えているのに false だと拒否される")
        void annualFlagIsDerived() {
            rejectedBy("monthly_settlements_annual_agreement_check", () -> settlement()
                    .working(13_000).target(13_000)
                    .dailyOvertime(2_760).weeklyOvertime(0).overtime(2_760)
                    .annualBefore(21_000).exceedsMonthly(true).exceedsAnnual(false)
                    .insert());
        }

        /**
         * <strong>限度時間の対象は時間外労働だけ</strong>（36 条 3 項）。
         * 第 2 版は休日労働を合算しており、時間外 44 時間 + 法定休日 8 時間という
         * <strong>適法な月を「上限超過」と記録していた。</strong>
         */
        @Test
        @DisplayName("IT-SET-26 時間外 44 時間 + 法定休日 8 時間は限度時間を超えない")
        void legalHolidayIsNotSubjectToTheLimit() {
            accepted(() -> settlement()
                    .working(13_000).legalHoliday(480).target(12_520)
                    .dailyOvertime(2_640).weeklyOvertime(0).overtime(2_640)
                    .exceedsMonthly(false)
                    .insert());
        }

        /**
         * 36 条 6 項 2 号は時間外 + 休日で <strong>100 時間「未満」</strong>。
         * 「以下」と書くと、ちょうど 100 時間の月を見逃す。
         */
        @Test
        @DisplayName("IT-SET-27 時間外 + 休日がちょうど 100 時間なのに false だと拒否される")
        void combinedFlagIsDerived() {
            rejectedBy("monthly_settlements_combined_limit_check", () -> settlement()
                    .working(13_000).legalHoliday(3_360).target(9_640)
                    .dailyOvertime(2_640).weeklyOvertime(0).overtime(2_640)
                    .exceedsMonthly(false).exceedsCombined(false)
                    .insert());
        }
    }

    @Nested
    @DisplayName("週 40 時間超の内訳")
    class Weekly {

        private UUID settlementId;

        @BeforeEach
        void setUpSettlement() {
            settlementId = settlement().insert();
        }

        private void week(LocalDate start, LocalDate endExclusive, int inside, int overtime) {
            jdbc.update("""
                    INSERT INTO weekly_overtimes (id, monthly_settlement_id, week_start,
                            week_end_exclusive, statutory_inside_minutes, overtime_minutes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Fixtures.id(), settlementId, start, endExclusive, inside, overtime);
        }

        @Test
        @DisplayName("IT-SET-09 週の起算日が日曜でないと拒否される")
        void weekMustStartOnSunday() {
            rejectedBy("weekly_overtimes_start_dow_check",
                    () -> week(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 11), 2_400, 0));
        }

        @Test
        @DisplayName("IT-SET-10 週が 7 日間でないと拒否される")
        void weekMustBeSevenDays() {
            rejectedBy("weekly_overtimes_span_check",
                    () -> week(LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 9), 2_400, 0));
        }

        @Test
        @DisplayName("IT-SET-11 週の時間外が 40 時間の超過分と一致しないと拒否される")
        void weeklyOvertimeIsDerived() {
            rejectedBy("weekly_overtimes_calculation_check",
                    () -> week(LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 10), 2_520, 60));
        }

        @Test
        @DisplayName("40 時間を超えた分がそのまま時間外になる週は登録できる")
        void normalWeek() {
            accepted(() -> week(LocalDate.of(2026, 5, 3), LocalDate.of(2026, 5, 10), 2_520, 120));
        }
    }

    @Test
    @DisplayName("IT-SET-20 updated_at が UPDATE で更新される")
    void updatedAtIsMaintained() {
        UUID id = settlement().insert();
        assertThat(jdbc.queryForObject(
                "SELECT updated_at = created_at FROM monthly_settlements WHERE id = ?",
                Boolean.class, id)).isTrue();

        jdbc.update("UPDATE monthly_settlements SET version = version + 1 WHERE id = ?", id);

        assertThat(jdbc.queryForObject(
                "SELECT updated_at > created_at FROM monthly_settlements WHERE id = ?",
                Boolean.class, id)).isTrue();
    }
}
