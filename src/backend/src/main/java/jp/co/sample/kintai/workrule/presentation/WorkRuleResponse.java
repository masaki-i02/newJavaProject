package jp.co.sample.kintai.workrule.presentation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jp.co.sample.kintai.workrule.domain.FixedTimeSystem;
import jp.co.sample.kintai.workrule.domain.FlextimeSystem;
import jp.co.sample.kintai.workrule.domain.WorkRule;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeries;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 就業規則の応答（API 設計書 2.1）。
 *
 * <p><strong>制度ごとの項目を入れ子に分ける。</strong>
 * 平坦に並べると「FLEX なのに始業時刻がある」形になり、
 * DB の CHECK 制約が禁じた状態を API が再現してしまう。
 *
 * <p><strong>使わないほうのキーは出力しない</strong>（{@code null} も出さない）。
 * TypeScript 側で判別可能なユニオンとして受けられるようにするため。
 * {@code @JsonInclude} は<strong>項目ごとに付ける</strong>（落とし穴 76）。
 *
 * <p><strong>割増率は文字列で返す。</strong>
 * {@code 0.250} を JSON の数値にすると、受け手の言語によっては
 * 浮動小数点になり丸め誤差が入る。
 */
public record WorkRuleResponse(String seriesId, String name,
                               @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate abolishedOn,
                               long version, List<Revision> revisions) {

    public static WorkRuleResponse of(WorkRuleSeries series, List<WorkRule> revisions) {
        return new WorkRuleResponse(series.id().value().toString(), series.name(),
                series.abolishedOn().orElse(null), series.version(),
                revisions.stream()
                        .sorted(java.util.Comparator.comparing(r -> r.validPeriod().from()))
                        .map(Revision::of).toList());
    }

    /** 系列だけの行（一覧）。版の履歴は含めない。 */
    public static WorkRuleResponse summaryOf(WorkRuleSeries series) {
        return new WorkRuleResponse(series.id().value().toString(), series.name(),
                series.abolishedOn().orElse(null), series.version(), null);
    }

    /**
     * 版。
     *
     * <p>期間の上限を <strong>{@code validToExclusive}</strong> という名前で返す。
     * 「その日を含むのか」を名前で示す。ドメインも DB も半開区間で統一している。
     */
    public record Revision(String workRuleId, LocalDate validFrom,
                           @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate validToExclusive,
                           String workingTimeSystem,
                           @JsonInclude(JsonInclude.Include.NON_NULL) FixedTime fixedTime,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Flextime flextime,
                           long statutoryDailyMinutes, long statutoryWeeklyMinutes,
                           NightWindowResponse nightWindow, PremiumRatesResponse premiumRates) {

        static Revision of(WorkRule rule) {
            // ★ 分岐は sealed interface に対する網羅性検査つき switch。
            //   default を書かないので、制度を足した瞬間にここがコンパイルエラーになる
            FixedTime fixed = switch (rule.workingTimeSystem()) {
                case FixedTimeSystem system -> FixedTime.of(system);
                case FlextimeSystem ignored -> null;
            };
            Flextime flex = switch (rule.workingTimeSystem()) {
                case FixedTimeSystem ignored -> null;
                case FlextimeSystem system -> Flextime.of(system);
            };
            // ★ enum は永続化と表示のためだけに使う。分岐は上の switch が持つ
            String type = switch (rule.workingTimeSystem()) {
                case FixedTimeSystem ignored -> WorkingTimeSystemType.FIXED.name();
                case FlextimeSystem ignored -> WorkingTimeSystemType.FLEX.name();
            };
            return new Revision(rule.id().value().toString(),
                    rule.validPeriod().from(),
                    rule.validPeriod().isUnbounded() ? null : rule.validPeriod().toExclusive(),
                    type, fixed, flex,
                    rule.statutoryDailyWorkingTime().toMinutes(),
                    rule.statutoryWeeklyWorkingTime().toMinutes(),
                    new NightWindowResponse(rule.nightWindow().name(),
                            rule.nightWindow().start(), rule.nightWindow().end()),
                    PremiumRatesResponse.of(rule));
        }
    }

    /** 固定時間制の項目。 */
    public record FixedTime(LocalTime scheduledStart, LocalTime scheduledEnd,
                            long scheduledBreakMinutes, long scheduledWorkingMinutes) {

        static FixedTime of(FixedTimeSystem system) {
            return new FixedTime(system.scheduledStart(), system.scheduledEnd(),
                    system.scheduledBreak().toMinutes(),
                    system.scheduledWorkingTime().toMinutes());
        }
    }

    /** フレックスタイム制の項目。 */
    public record Flextime(LocalTime flexibleStart, LocalTime flexibleEnd,
                           LocalTime coreStart, LocalTime coreEnd,
                           long standardDailyMinutes) {

        static Flextime of(FlextimeSystem system) {
            return new Flextime(system.flexibleTime().start(), system.flexibleTime().end(),
                    system.coreTime().start(), system.coreTime().end(),
                    system.standardDailyWorkingTime().toMinutes());
        }
    }

    /**
     * 深夜帯。
     *
     * <p>名前も返す。法が認める値は 2 つしかないので、
     * 受け取る側は時刻ではなく名前で分岐できる。
     */
    public record NightWindowResponse(String name, LocalTime start, LocalTime end) {
    }

    /** 割増率。文字列で返す（丸め誤差を持ち込ませない）。 */
    public record PremiumRatesResponse(String overtimeBeyondStatutory, String night,
                                       String legalHoliday) {

        static PremiumRatesResponse of(WorkRule rule) {
            return new PremiumRatesResponse(
                    rule.premiumRates().overtimeBeyondStatutory().toPlainString(),
                    rule.premiumRates().night().toPlainString(),
                    rule.premiumRates().legalHoliday().toPlainString());
        }
    }
}
