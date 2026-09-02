package jp.co.sample.kintai.workrule.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;

/**
 * 割増率（労基法 37 条）。
 *
 * <p><strong>すべて小数第 3 位に正規化して保持する。</strong>
 * {@code record} の {@code equals} は {@link BigDecimal#equals} を使い、
 * これは値だけでなく <em>スケール</em> も比較する。DB の {@code NUMERIC(4,3)} は
 * {@code 0.250} を返すため、正規化しないと定数 {@code 0.25} と等しくならず、
 * 読み書きを往復しただけで別の値になってしまう。
 *
 * @param overtimeBeyondStatutory 法定外残業。0.25 以上
 * @param night                   深夜。0.25 以上
 * @param legalHoliday            法定休日労働。0.35 以上
 */
public record PremiumRates(BigDecimal overtimeBeyondStatutory, BigDecimal night,
                           BigDecimal legalHoliday) {

    /** DB の {@code NUMERIC(4,3)} にそろえる。 */
    private static final int SCALE = 3;

    private static final BigDecimal MIN_OVERTIME = new BigDecimal("0.250");
    private static final BigDecimal MIN_NIGHT = new BigDecimal("0.250");
    private static final BigDecimal MIN_LEGAL_HOLIDAY = new BigDecimal("0.350");

    /** 法定どおりの割増率。 */
    public static final PremiumRates STATUTORY =
            new PremiumRates(MIN_OVERTIME, MIN_NIGHT, MIN_LEGAL_HOLIDAY);

    public PremiumRates {
        overtimeBeyondStatutory = normalize(overtimeBeyondStatutory, MIN_OVERTIME, "法定外残業");
        night = normalize(night, MIN_NIGHT, "深夜");
        legalHoliday = normalize(legalHoliday, MIN_LEGAL_HOLIDAY, "法定休日");
    }

    private static BigDecimal normalize(BigDecimal rate, BigDecimal minimum, String label) {
        if (rate == null) {
            throw new IllegalArgumentException("%sの割増率に null は許されません".formatted(label));
        }
        if (rate.compareTo(minimum) < 0) {
            throw new BusinessRuleViolationException("BR-06",
                    "%sの割増率が法定下限を下回っています: %s < %s".formatted(label, rate, minimum));
        }
        try {
            return rate.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "%sの割増率は小数第 %d 位までです: %s".formatted(label, SCALE, rate), e);
        }
    }

    /**
     * 割増属性の組み合わせに対する賃金倍率。
     *
     * <p>深夜は他の区分に<strong>重ねて</strong>加算する。
     * 法定休日労働と時間外労働は重複しないので、両方が付くことはない。
     *
     * <pre>
     * 深夜 + 法定外残業 → 1.000 + 0.250 + 0.250 = 1.500
     * 深夜 + 法定休日   → 1.000 + 0.350 + 0.250 = 1.600
     * </pre>
     */
    public BigDecimal multiplierFor(Set<PremiumType> premiums) {
        BigDecimal multiplier = BigDecimal.ONE;
        if (premiums.contains(PremiumType.LEGAL_HOLIDAY)) {
            multiplier = multiplier.add(legalHoliday);
        } else if (premiums.contains(PremiumType.OVERTIME_BEYOND_STATUTORY)) {
            multiplier = multiplier.add(overtimeBeyondStatutory);
        }
        // 法定内残業（OVERTIME_WITHIN_STATUTORY）に割増の支払義務は無い
        if (premiums.contains(PremiumType.NIGHT)) {
            multiplier = multiplier.add(night);
        }
        // 呼び出し側が倍率どうしを equals で比べられるよう、スケールをそろえて返す
        return multiplier.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
