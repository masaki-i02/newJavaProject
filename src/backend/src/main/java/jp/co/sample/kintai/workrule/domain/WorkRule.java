package jp.co.sample.kintai.workrule.domain;

import java.time.Duration;

import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/**
 * 就業規則の<strong>版</strong>。改定のたびに 1 つ増える。
 *
 * @param id                         版の識別子
 * @param seriesId                   系列の識別子。社員はこちらに紐づく
 * @param validPeriod                この版が有効な期間。半開区間
 * @param workingTimeSystem          労働時間制度
 * @param statutoryDailyWorkingTime  1 日の法定労働時間。原則 8 時間。<strong>これを超える値は作れない</strong>
 * @param statutoryWeeklyWorkingTime 1 週の法定労働時間。原則 40 時間。同上
 * @param nightWindow                深夜帯
 * @param premiumRates               割増率
 */
public record WorkRule(WorkRuleId id, WorkRuleSeriesId seriesId, DateRange validPeriod,
                       WorkingTimeSystem workingTimeSystem,
                       Duration statutoryDailyWorkingTime,
                       Duration statutoryWeeklyWorkingTime,
                       NightWindow nightWindow, PremiumRates premiumRates) {

    /** 労基法 32 条。 */
    public static final Duration STATUTORY_DAILY = Duration.ofHours(8);
    public static final Duration STATUTORY_WEEKLY = Duration.ofHours(40);

    public WorkRule {
        if (id == null || seriesId == null || validPeriod == null || workingTimeSystem == null
                || nightWindow == null || premiumRates == null) {
            throw new IllegalArgumentException("就業規則の項目に null は許されません");
        }
        requireWithin(statutoryDailyWorkingTime, STATUTORY_DAILY, "1 日の法定労働時間");
        requireWithin(statutoryWeeklyWorkingTime, STATUTORY_WEEKLY, "1 週の法定労働時間");
        if (statutoryDailyWorkingTime.compareTo(statutoryWeeklyWorkingTime) > 0) {
            throw new IllegalArgumentException(
                    "1 日の法定労働時間が 1 週を超えています: %s > %s"
                            .formatted(statutoryDailyWorkingTime, statutoryWeeklyWorkingTime));
        }
        requireScheduledWithinStatutory(workingTimeSystem, statutoryDailyWorkingTime);
    }

    /**
     * 法定値には<strong>下限だけでなく上限</strong>も置く。
     *
     * <p>割増率の下限を守っても、法定労働時間を 12 時間にされたら
     * 割増の対象そのものが消える（CLAUDE.md 落とし穴 15）。
     */
    private static void requireWithin(Duration actual, Duration maximum, String label) {
        if (actual == null) {
            throw new IllegalArgumentException("%sに null は許されません".formatted(label));
        }
        if (!actual.isPositive()) {
            throw new IllegalArgumentException("%sは正である必要があります: %s".formatted(label, actual));
        }
        if (actual.compareTo(maximum) > 0) {
            // 人事が登録画面から踏める誤りなので、業務エラーとして 422 に載せる
            throw new BusinessRuleViolationException("BR-04",
                    "%sが法定の上限を超えています: %s > %s".formatted(label, actual, maximum));
        }
    }

    /**
     * この規則の 1 日の所定労働時間。
     *
     * <p><strong>フレックスに「日々の所定」があるという意味ではない。</strong>
     * フレックスの所定は清算期間の総労働時間（労基法 32 条の 3）であり、
     * {@code standardDailyWorkingTime} はそれを算出するための<strong>係数</strong>である
     * （就業規則 ドメインモデル設計書）。日々の残業判定には使わない。
     *
     * <p>用途は 2 つある。
     * <ul>
     *   <li>所定総労働時間の算出（{@code attendance} の月次清算）</li>
     *   <li><strong>割増賃金の基礎額の分母</strong>（労基則 19 条 1 項 4 号。BR-18）</li>
     * </ul>
     *
     * <p><strong>この {@code switch} を写さない。</strong>
     * 制度を追加したときに直す場所が増える（CLAUDE.md 落とし穴 67）。
     */
    public Duration scheduledDailyWorkingTime() {
        return scheduledDailyWorkingTimeOf(workingTimeSystem);
    }

    private static Duration scheduledDailyWorkingTimeOf(WorkingTimeSystem system) {
        return switch (system) {
            case FixedTimeSystem fixed -> fixed.scheduledWorkingTime();
            case FlextimeSystem flex -> flex.standardDailyWorkingTime();
        };
    }

    /** 所定労働時間は法定労働時間を超えられない。超えるならそれは「所定」ではなく残業である。 */
    private static void requireScheduledWithinStatutory(WorkingTimeSystem system,
                                                        Duration statutoryDaily) {
        Duration scheduled = scheduledDailyWorkingTimeOf(system);
        if (scheduled.compareTo(statutoryDaily) > 0) {
            throw new BusinessRuleViolationException("BR-04",
                    "所定労働時間が法定労働時間を超えています: %s > %s"
                            .formatted(scheduled, statutoryDaily));
        }
    }

    /** 永続化と表示のための判別値。<strong>分岐には使わない。</strong> */
    public WorkingTimeSystemType systemType() {
        return WorkingTimeSystemType.of(workingTimeSystem);
    }
}
