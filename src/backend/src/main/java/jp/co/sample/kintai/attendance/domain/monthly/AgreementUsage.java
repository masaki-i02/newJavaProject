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
 * @param overtimeTime      その月の時間外労働（法定外残業）
 * @param legalHolidayTime  その月の法定休日労働
 * @param monthlyLimit      月次上限。原則 45 時間
 * @param annualLimit       年次上限。原則 360 時間
 * @param annualUsedBefore  当年度の<strong>当月より前</strong>の累計
 */
public record AgreementUsage(Duration overtimeTime, Duration legalHolidayTime,
                             Duration monthlyLimit, Duration annualLimit,
                             Duration annualUsedBefore) {

    /** 労基法 36 条の原則。特別条項は扱わない。 */
    public static final Duration DEFAULT_MONTHLY_LIMIT = Duration.ofHours(45);
    public static final Duration DEFAULT_ANNUAL_LIMIT = Duration.ofHours(360);

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
     * 36 協定の対象時間。
     *
     * <p><strong>法定内残業は含めない。</strong> 時間外労働ではないので 36 条の対象外である。
     * 一方 <strong>法定休日労働は含める。</strong>
     * 時間外労働への算入はしない（BR-07）が、36 協定の時間数には算入する。
     */
    public Duration subjectTime() {
        return overtimeTime.plus(legalHolidayTime);
    }

    /** 当年度の累計（当月を含む）。 */
    public Duration annualUsed() {
        return annualUsedBefore.plus(subjectTime());
    }

    public boolean exceedsMonthly() {
        return subjectTime().compareTo(monthlyLimit) > 0;
    }

    public boolean exceedsAnnual() {
        return annualUsed().compareTo(annualLimit) > 0;
    }

    public boolean hasWarning() {
        return exceedsMonthly() || exceedsAnnual();
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
