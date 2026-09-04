package jp.co.sample.kintai.attendance.domain.monthly;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 36 協定の消化状況（BR-12）。
 *
 * <p><strong>上限を超えても登録を拒否しない。</strong>
 * システムが労働を止めることはできない。既に働いた事実を記録できなくすると、
 * 記録が実態と乖離し、かえって労務リスクが上がる。
 * 警告を出すだけにとどめ、是正は人事と上長の運用で行う。
 *
 * <p><strong>2 つの規制を混ぜない。</strong>
 * 限度時間（36 条 3 項・4 項）の対象は<strong>時間外労働だけ</strong>で、休日労働は含まない。
 * 休日労働を含めるのは 36 条 6 項の別の規制（単月 100 時間未満・複数月平均 80 時間以内）である。
 * 混ぜると、時間外 44 時間 + 法定休日 8 時間という<strong>適法な月に偽の警告が立つ。</strong>
 *
 * @param overtimeTime      その月の時間外労働（法定外残業）。<strong>限度時間の対象</strong>
 * @param legalHolidayTime  その月の法定休日労働。限度時間には数えず、単月 100 時間の判定に使う
 * @param monthlyLimit      限度時間の月次上限。原則 45 時間
 * @param annualLimit       限度時間の年次上限。原則 360 時間
 * @param annualUsedBefore  当年度の<strong>当月より前</strong>の時間外労働の累計
 */
public record AgreementUsage(Duration overtimeTime, Duration legalHolidayTime,
                             Duration monthlyLimit, Duration annualLimit,
                             Duration annualUsedBefore) {

    /** 労基法 36 条 4 項の限度時間。特別条項は扱わない。 */
    public static final Duration DEFAULT_MONTHLY_LIMIT = Duration.ofHours(45);
    public static final Duration DEFAULT_ANNUAL_LIMIT = Duration.ofHours(360);

    /**
     * 労基法 36 条 6 項 2 号。<strong>時間外労働と休日労働の合計</strong>の単月上限。
     *
     * <p>限度時間と違い <strong>100 時間「未満」</strong>であり、ちょうど 100 時間で違反になる。
     * 特別条項の有無にかかわらず超えられない絶対の上限である。
     */
    public static final Duration COMBINED_SINGLE_MONTH_LIMIT = Duration.ofHours(100);

    /** 年度の起算月。対象企業の事業年度に合わせる。 */
    public static final int FISCAL_YEAR_START_MONTH = 4;

    public AgreementUsage {
        if (overtimeTime == null || legalHolidayTime == null || monthlyLimit == null
                || annualLimit == null || annualUsedBefore == null) {
            throw new IllegalArgumentException("36 協定の項目に null は許されません");
        }
        if (overtimeTime.isNegative() || legalHolidayTime.isNegative()
                || annualUsedBefore.isNegative()) {
            throw new IllegalArgumentException("36 協定の実績を負にはできません");
        }
        if (!monthlyLimit.isPositive() || !annualLimit.isPositive()) {
            throw new IllegalArgumentException("36 協定の上限は正である必要があります");
        }
        if (monthlyLimit.compareTo(annualLimit) > 0) {
            throw new IllegalArgumentException(
                    "月次上限が年次上限を超えています: 月 %s / 年 %s"
                            .formatted(monthlyLimit, annualLimit));
        }
    }

    /** 原則の上限で作る。 */
    public static AgreementUsage of(Duration overtimeTime, Duration legalHolidayTime,
                                    Duration annualUsedBefore) {
        return new AgreementUsage(overtimeTime, legalHolidayTime,
                DEFAULT_MONTHLY_LIMIT, DEFAULT_ANNUAL_LIMIT, annualUsedBefore);
    }

    /**
     * 限度時間の対象になる時間（36 条 3 項）。
     *
     * <p><strong>時間外労働だけ。</strong>
     * 法定内残業は時間外労働ではないので含めない。
     * 法定休日労働も含めない。休日労働は限度時間の対象ではなく、
     * 36 条 6 項の 100 時間未満・複数月平均 80 時間以内で規制される。
     */
    public Duration subjectTime() {
        return overtimeTime;
    }

    /**
     * 時間外労働と休日労働の合計（36 条 6 項）。
     *
     * <p>限度時間とは<strong>別のものさし</strong>である。
     */
    public Duration combinedTime() {
        return overtimeTime.plus(legalHolidayTime);
    }

    /** 当年度の時間外労働の累計（当月を含む）。 */
    public Duration annualUsed() {
        return annualUsedBefore.plus(subjectTime());
    }

    public boolean exceedsMonthly() {
        return subjectTime().compareTo(monthlyLimit) > 0;
    }

    public boolean exceedsAnnual() {
        return annualUsed().compareTo(annualLimit) > 0;
    }

    /**
     * 単月 100 時間未満（36 条 6 項 2 号）に触れているか。
     *
     * <p><strong>「以下」ではなく「未満」。</strong> ちょうど 100 時間で違反になる。
     */
    public boolean exceedsCombinedSingleMonth() {
        return combinedTime().compareTo(COMBINED_SINGLE_MONTH_LIMIT) >= 0;
    }

    public boolean hasWarning() {
        return exceedsMonthly() || exceedsAnnual() || exceedsCombinedSingleMonth();
    }

    /**
     * その月が属する年度の開始日。
     *
     * <p>4 月 1 日起算。1〜3 月は<strong>前年</strong>の 4 月 1 日が起算日になる。
     * 暦年で数えると、1 月に年次上限がリセットされて 3 か月ぶんの超過を見逃す。
     */
    public static LocalDate fiscalYearStartOf(YearMonth month) {
        int year = month.getMonthValue() >= FISCAL_YEAR_START_MONTH
                ? month.getYear()
                : month.getYear() - 1;
        return LocalDate.of(year, FISCAL_YEAR_START_MONTH, 1);
    }
}
