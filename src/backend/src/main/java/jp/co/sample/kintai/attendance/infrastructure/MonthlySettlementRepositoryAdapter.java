package jp.co.sample.kintai.attendance.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.attendance.domain.monthly.AgreementUsage;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlement;
import jp.co.sample.kintai.attendance.domain.monthly.MonthlySettlementRepository;
import jp.co.sample.kintai.attendance.domain.monthly.WeeklyOvertime;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.SettlementPeriod;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * {@link MonthlySettlementRepository} の実装。
 *
 * <p>{@code JdbcTemplate} を使う。清算結果は<strong>まるごと入れ替える</strong>表であり、
 * 同一性の管理も変更の追跡も要らない（CLAUDE.md 2.2）。
 *
 * <p><strong>{@code calculated_at} は {@link Clock} から採る。</strong>
 * 業務上の日時なのでテストで固定できる必要がある。
 * 一方 {@code created_at} / {@code updated_at} は DB の {@code now()} が入れる。
 * 監査のための時刻はアプリケーションから偽装できてはならない（CLAUDE.md 2.3）。
 */
@Repository
class MonthlySettlementRepositoryAdapter implements MonthlySettlementRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    MonthlySettlementRepositoryAdapter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void save(MonthlySettlement settlement) {
        LocalDate targetMonth = settlement.period().month().atDay(1);
        UUID employeeId = settlement.employeeId().value();

        // 再計算なので、同じ社員・同じ対象月の行は週の内訳ごと消して入れ直す。
        // ON DELETE CASCADE により weekly_overtimes も消える
        jdbc.update("DELETE FROM monthly_settlements WHERE employee_id = ? "
                + "AND target_month = ?", employeeId, targetMonth);

        UUID id = UUID.randomUUID();
        AgreementUsage usage = settlement.agreementUsage();
        jdbc.update("""
                INSERT INTO monthly_settlements (id, employee_id, target_month,
                        period_from, period_to_exclusive, work_rule_series_id,
                        working_time_system, working_minutes, legal_holiday_minutes,
                        target_working_minutes, scheduled_total_minutes,
                        statutory_total_limit_minutes, daily_overtime_minutes,
                        weekly_overtime_minutes, carried_over_overtime_minutes,
                        overtime_minutes, shortage_minutes, night_minutes,
                        annual_agreement_subject_before_minutes,
                        monthly_agreement_limit_minutes, annual_agreement_limit_minutes,
                        exceeds_monthly_agreement_limit, exceeds_annual_agreement_limit,
                        exceeds_combined_single_month_limit, calculated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?)
                """,
                id, employeeId, targetMonth,
                settlement.period().period().from(),
                settlement.period().period().toExclusive(),
                settlement.workRuleSeriesId().value(),
                settlement.workingTimeSystem().name(),
                minutes(settlement.workingTime()), minutes(settlement.legalHolidayTime()),
                minutes(settlement.targetWorkingTime()),
                minutes(settlement.scheduledTotalTime()),
                minutes(settlement.statutoryTotalLimit()),
                minutes(settlement.dailyOvertimeTime()),
                minutes(settlement.weeklyOvertimeTime()),
                minutes(settlement.carriedOverOvertimeTime()),
                minutes(settlement.overtimeTime()), minutes(settlement.shortageTime()),
                minutes(settlement.nightTime()),
                minutes(usage.annualUsedBefore()),
                minutes(usage.monthlyLimit()), minutes(usage.annualLimit()),
                usage.exceedsMonthly(), usage.exceedsAnnual(),
                usage.exceedsCombinedSingleMonth(),
                java.time.OffsetDateTime.now(clock));

        for (WeeklyOvertime week : settlement.weeklyBreakdown()) {
            jdbc.update("""
                    INSERT INTO weekly_overtimes (id, monthly_settlement_id, week_start,
                            week_end_exclusive, statutory_inside_minutes, overtime_minutes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), id, week.weekStart(), week.weekEndExclusive(),
                    minutes(week.statutoryInsideTime()), minutes(week.overtimeTime()));
        }
    }

    @Override
    public Optional<MonthlySettlement> find(EmployeeId employeeId, YearMonth month) {
        List<Row> rows = jdbc.query("""
                SELECT id, period_from, period_to_exclusive, work_rule_series_id,
                       working_time_system, working_minutes, legal_holiday_minutes,
                       target_working_minutes, scheduled_total_minutes,
                       statutory_total_limit_minutes, daily_overtime_minutes,
                       weekly_overtime_minutes, carried_over_overtime_minutes,
                       overtime_minutes, shortage_minutes, night_minutes,
                       annual_agreement_subject_before_minutes,
                       monthly_agreement_limit_minutes, annual_agreement_limit_minutes
                FROM monthly_settlements
                WHERE employee_id = ? AND target_month = ?
                """,
                (rs, rowNum) -> new Row(
                        (UUID) rs.getObject("id"),
                        rs.getObject("period_from", LocalDate.class),
                        rs.getObject("period_to_exclusive", LocalDate.class),
                        (UUID) rs.getObject("work_rule_series_id"),
                        rs.getString("working_time_system"),
                        rs.getInt("working_minutes"), rs.getInt("legal_holiday_minutes"),
                        rs.getInt("target_working_minutes"),
                        rs.getInt("scheduled_total_minutes"),
                        rs.getInt("statutory_total_limit_minutes"),
                        rs.getInt("daily_overtime_minutes"),
                        rs.getInt("weekly_overtime_minutes"),
                        rs.getInt("carried_over_overtime_minutes"),
                        rs.getInt("overtime_minutes"), rs.getInt("shortage_minutes"),
                        rs.getInt("night_minutes"),
                        rs.getInt("annual_agreement_subject_before_minutes"),
                        rs.getInt("monthly_agreement_limit_minutes"),
                        rs.getInt("annual_agreement_limit_minutes")),
                employeeId.value(), month.atDay(1));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row row = rows.getFirst();
        return Optional.of(new MonthlySettlement(
                employeeId,
                new SettlementPeriod(month, new DateRange(row.from(), row.toExclusive())),
                new WorkRuleSeriesId(row.seriesId()),
                WorkingTimeSystemType.valueOf(row.system()),
                of(row.working()), of(row.legalHoliday()), of(row.target()),
                of(row.scheduledTotal()), of(row.statutoryLimit()),
                of(row.dailyOvertime()), of(row.weeklyOvertime()), of(row.carriedOver()),
                of(row.overtime()), of(row.shortage()), of(row.night()),
                weeksOf(row.id()),
                new AgreementUsage(of(row.overtime()), of(row.legalHoliday()),
                        of(row.monthlyLimit()), of(row.annualLimit()),
                        of(row.annualBefore()))));
    }

    /**
     * 当年度の、指定月より前の 36 協定対象時間の累計。
     *
     * <p><strong>限度時間の対象は時間外労働だけ</strong>で、休日労働は含まない（36 条 3 項）。
     * 休日労働を足すのは 6 項 2 号の単月 100 時間の判定であり、別の規制である。
     */
    @Override
    public Duration annualSubjectTimeBefore(EmployeeId employeeId, YearMonth month) {
        // 年度の起算はドメイン（AgreementUsage）が決める。ここで数え直すと 2 か所になる
        LocalDate fiscalStart = AgreementUsage.fiscalYearStartOf(month);
        Integer total = jdbc.queryForObject("""
                SELECT coalesce(sum(overtime_minutes), 0)
                FROM monthly_settlements
                WHERE employee_id = ? AND target_month >= ? AND target_month < ?
                """, Integer.class, employeeId.value(), fiscalStart, month.atDay(1));
        return Duration.ofMinutes(total == null ? 0 : total);
    }

    private List<WeeklyOvertime> weeksOf(UUID settlementId) {
        return jdbc.query("""
                SELECT week_start, week_end_exclusive, statutory_inside_minutes,
                       overtime_minutes
                FROM weekly_overtimes
                WHERE monthly_settlement_id = ?
                ORDER BY week_start
                """,
                (rs, rowNum) -> new WeeklyOvertime(
                        rs.getObject("week_start", LocalDate.class),
                        rs.getObject("week_end_exclusive", LocalDate.class),
                        of(rs.getInt("statutory_inside_minutes")),
                        of(rs.getInt("overtime_minutes"))),
                settlementId);
    }

    private static int minutes(Duration duration) {
        return Math.toIntExact(duration.toMinutes());
    }

    private static Duration of(int minutes) {
        return Duration.ofMinutes(minutes);
    }

    /** 読み出した 1 行。列が多いので、変換前の素の値をまとめて運ぶ。 */
    private record Row(UUID id, LocalDate from, LocalDate toExclusive, UUID seriesId,
                       String system, int working, int legalHoliday, int target,
                       int scheduledTotal, int statutoryLimit, int dailyOvertime,
                       int weeklyOvertime, int carriedOver, int overtime, int shortage,
                       int night, int annualBefore, int monthlyLimit, int annualLimit) {
    }
}
