package jp.co.sample.kintai.workrule.infrastructure;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.TimeOfDayRange;
import jp.co.sample.kintai.workrule.domain.FixedTimeSystem;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.PremiumRates;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleId;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystem;

/**
 * 就業規則のドメイン ⇄ エンティティ変換。
 *
 * <p>無期限は番兵 ⇄ {@code NULL} で写す（CLAUDE.md 落とし穴 35）。
 * 深夜帯は<strong>列の時刻から enum を引き当てる。</strong>
 * 法が認める 2 つ以外の値が入っていたら、それは DB の
 * {@code work_rules_night_window_check} が壊れた証拠なので例外にする。
 */
final class WorkRuleMapper {

    private WorkRuleMapper() {
    }

    static WorkRule toDomain(WorkRuleEntity entity) {
        return new WorkRule(
                new WorkRuleId(entity.getId()),
                new WorkRuleSeriesId(entity.getSeriesId()),
                toRange(entity.getValidFrom(), entity.getValidTo()),
                toSystem(entity),
                Duration.ofMinutes(entity.getStatutoryDailyMinutes()),
                Duration.ofMinutes(entity.getStatutoryWeeklyMinutes()),
                toNightWindow(entity),
                new PremiumRates(entity.getRateOvertime(), entity.getRateNight(),
                        entity.getRateLegalHoliday()));
    }

    static void apply(WorkRule rule, WorkRuleEntity entity) {
        entity.setSeriesId(rule.seriesId().value());
        entity.setValidFrom(rule.validPeriod().from());
        entity.setValidTo(toColumn(rule.validPeriod()));
        entity.setStatutoryDailyMinutes((int) rule.statutoryDailyWorkingTime().toMinutes());
        entity.setStatutoryWeeklyMinutes((int) rule.statutoryWeeklyWorkingTime().toMinutes());
        entity.setNightStart(rule.nightWindow().start());
        entity.setNightEnd(rule.nightWindow().end());
        entity.setRateOvertime(rule.premiumRates().overtimeBeyondStatutory());
        entity.setRateNight(rule.premiumRates().night());
        entity.setRateLegalHoliday(rule.premiumRates().legalHoliday());

        // ★ default 句を書かない。制度を追加した瞬間にここがコンパイルエラーになる
        switch (rule.workingTimeSystem()) {
            case FixedTimeSystem fixed -> {
                entity.setWorkingTimeSystem("FIXED");
                entity.setScheduledStart(fixed.scheduledStart());
                entity.setScheduledEnd(fixed.scheduledEnd());
                entity.setScheduledBreakMinutes((int) fixed.scheduledBreak().toMinutes());
                // 反対の制度の列は必ず空にする（work_rules_variant_check）
                entity.setFlexibleStart(null);
                entity.setFlexibleEnd(null);
                entity.setCoreStart(null);
                entity.setCoreEnd(null);
                entity.setStandardDailyMinutes(null);
            }
            case FlextimeSystem flex -> {
                entity.setWorkingTimeSystem("FLEX");
                entity.setFlexibleStart(flex.flexibleTime().start());
                entity.setFlexibleEnd(flex.flexibleTime().end());
                entity.setCoreStart(flex.coreTime().start());
                entity.setCoreEnd(flex.coreTime().end());
                entity.setStandardDailyMinutes(
                        (int) flex.standardDailyWorkingTime().toMinutes());
                entity.setScheduledStart(null);
                entity.setScheduledEnd(null);
                entity.setScheduledBreakMinutes(null);
            }
        }
    }

    private static WorkingTimeSystem toSystem(WorkRuleEntity entity) {
        return switch (entity.getWorkingTimeSystem()) {
            case "FIXED" -> new FixedTimeSystem(entity.getScheduledStart(),
                    entity.getScheduledEnd(),
                    Duration.ofMinutes(entity.getScheduledBreakMinutes()));
            case "FLEX" -> new FlextimeSystem(
                    new TimeOfDayRange(entity.getFlexibleStart(), entity.getFlexibleEnd()),
                    new TimeOfDayRange(entity.getCoreStart(), entity.getCoreEnd()),
                    Duration.ofMinutes(entity.getStandardDailyMinutes()));
            default -> throw new IllegalStateException(
                    "未知の労働時間制度が保存されています: " + entity.getWorkingTimeSystem());
        };
    }

    /**
     * 深夜帯の列から enum を引き当てる。
     *
     * <p>法が認めるのは 2 つだけで、DB の {@code work_rules_night_window_check} が
     * それを守っている。合致しない値が読めたなら制約が壊れているので、
     * <strong>もっともらしい値を作らずに例外にする。</strong>
     */
    private static NightWindow toNightWindow(WorkRuleEntity entity) {
        for (NightWindow candidate : NightWindow.values()) {
            if (candidate.start().equals(entity.getNightStart())
                    && candidate.end().equals(entity.getNightEnd())) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "法が認めない深夜帯が保存されています: %s–%s"
                        .formatted(entity.getNightStart(), entity.getNightEnd()));
    }

    static LocalDate toColumn(DateRange period) {
        return period.isUnbounded() ? null : period.toExclusive();
    }

    static DateRange toRange(LocalDate from, LocalDate toExclusive) {
        return toExclusive == null
                ? DateRange.startingAt(from)
                : new DateRange(from, toExclusive);
    }

    static Optional<LocalDate> toOptional(LocalDate value) {
        return Optional.ofNullable(value);
    }
}
