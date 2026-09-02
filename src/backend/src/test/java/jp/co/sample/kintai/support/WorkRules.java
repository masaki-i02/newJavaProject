package jp.co.sample.kintai.support;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

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
 * 就業規則を組み立てる。
 *
 * <p><strong>既定値は正常な値にする。</strong> ケースごとに変えたい 1 項目だけを
 * 上書きすれば、「入力を 1 つだけ変える」（CLAUDE.md 落とし穴 12）が自然に守られる。
 */
public final class WorkRules {

    private WorkRules() {
    }

    /** 9:00–18:00 / 休憩 60 分 = 所定 8 時間。 */
    public static FixedTimeSystem fixed() {
        return new FixedTimeSystem(LocalTime.of(9, 0), LocalTime.of(18, 0),
                Duration.ofMinutes(60));
    }

    public static FixedTimeSystem fixed(String start, String end, int breakMinutes) {
        return new FixedTimeSystem(LocalTime.parse(start), LocalTime.parse(end),
                Duration.ofMinutes(breakMinutes));
    }

    /** フレキシブル 07:00–22:00 / コア 11:00–15:00 / 1 日 8 時間。 */
    public static FlextimeSystem flex() {
        return new FlextimeSystem(
                new TimeOfDayRange(LocalTime.of(7, 0), LocalTime.of(22, 0)),
                new TimeOfDayRange(LocalTime.of(11, 0), LocalTime.of(15, 0)),
                Duration.ofHours(8));
    }

    /** 法定どおりの就業規則。 */
    public static WorkRule rule(WorkingTimeSystem system) {
        return new WorkRule(
                new WorkRuleId(UUID.randomUUID()),
                new WorkRuleSeriesId(UUID.randomUUID()),
                DateRange.startingAt(LocalDate.of(2026, 1, 1)),
                system,
                Duration.ofHours(8), Duration.ofHours(40),
                NightWindow.STANDARD, PremiumRates.STATUTORY);
    }

    public static WorkRule fixedRule() {
        return rule(fixed());
    }

    public static WorkRule flexRule() {
        return rule(flex());
    }
}
