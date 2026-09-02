package jp.co.sample.kintai.workrule.domain;

import java.math.BigDecimal;
import java.util.Set;

import jp.co.sample.kintai.shared.domain.PremiumType;

/**
 * 割増率（労基法 37 条）。
 *
 * @param overtimeBeyondStatutory 法定外残業。0.25 以上
 * @param night                   深夜。0.25 以上
 * @param legalHoliday            法定休日労働。0.35 以上
 */
public record PremiumRates(BigDecimal overtimeBeyondStatutory, BigDecimal night,
                           BigDecimal legalHoliday) {

    private static final BigDecimal MIN_OVERTIME = new BigDecimal("0.25");
    private static final BigDecimal MIN_NIGHT = new BigDecimal("0.25");
    private static final BigDecimal MIN_LEGAL_HOLIDAY = new BigDecimal("0.35");

    /** 法定どおりの割増率。 */
    public static final PremiumRates STATUTORY =
            new PremiumRates(MIN_OVERTIME, MIN_NIGHT, MIN_LEGAL_HOLIDAY);

    public PremiumRates {
        requireAtLeast(overtimeBeyondStatutory, MIN_OVERTIME, "法定外残業");
        requireAtLeast(night, MIN_NIGHT, "深夜");
        requireAtLeast(legalHoliday, MIN_LEGAL_HOLIDAY, "法定休日");
    }

    private static void requireAtLeast(BigDecimal rate, BigDecimal minimum, String label) {
        if (rate == null) {
            throw new IllegalArgumentException("%sの割増率に null は許されません".formatted(label));
        }
        if (rate.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                    "%sの割増率が法定下限を下回っています: %s < %s".formatted(label, rate, minimum));
        }
    }

    /**
     * 割増属性の組み合わせに対する賃金倍率。
     *
     * <p>深夜は他の区分に<strong>重ねて</strong>加算する。
     * 法定休日労働と時間外労働は重複しないので、両方が付くことはない。
     *
     * <pre>
     * 深夜 + 法定外残業 → 1.00 + 0.25 + 0.25 = 1.50
     * 深夜 + 法定休日   → 1.00 + 0.35 + 0.25 = 1.60
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
        return multiplier;
    }
}
