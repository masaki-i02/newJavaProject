package jp.co.sample.kintai.workrule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;
import jp.co.sample.kintai.shared.domain.PremiumType;

/** 割増率と賃金倍率（労基法 37 条）。 */
@DisplayName("割増率（労基法 37 条）")
class PremiumRatesTest {

    private static final PremiumRates RATES = PremiumRates.STATUTORY;

    @Nested
    @DisplayName("賃金倍率")
    class Multiplier {

        @Test
        @DisplayName("属性が無ければ 1.000")
        void plain() {
            assertThat(RATES.multiplierFor(Set.of())).isEqualByComparingTo("1.000");
        }

        /** 法定内残業に割増の支払義務は無い。 */
        @Test
        @DisplayName("法定内残業だけなら 1.000")
        void withinStatutoryHasNoPremium() {
            assertThat(RATES.multiplierFor(Set.of(PremiumType.OVERTIME_WITHIN_STATUTORY)))
                    .isEqualByComparingTo("1.000");
        }

        @Test
        @DisplayName("法定外残業は 1.250")
        void beyondStatutory() {
            assertThat(RATES.multiplierFor(Set.of(PremiumType.OVERTIME_BEYOND_STATUTORY)))
                    .isEqualByComparingTo("1.250");
        }

        @Test
        @DisplayName("法定休日は 1.350")
        void legalHoliday() {
            assertThat(RATES.multiplierFor(Set.of(PremiumType.LEGAL_HOLIDAY)))
                    .isEqualByComparingTo("1.350");
        }

        /** 深夜は他の区分に<strong>重ねて</strong>加算する。 */
        @Test
        @DisplayName("深夜だけなら 1.250")
        void nightAlone() {
            assertThat(RATES.multiplierFor(Set.of(PremiumType.NIGHT)))
                    .isEqualByComparingTo("1.250");
        }

        @Test
        @DisplayName("深夜 + 法定外残業は 1.500")
        void nightAndBeyondStatutory() {
            assertThat(RATES.multiplierFor(
                    Set.of(PremiumType.NIGHT, PremiumType.OVERTIME_BEYOND_STATUTORY)))
                    .isEqualByComparingTo("1.500");
        }

        @Test
        @DisplayName("深夜 + 法定休日は 1.600")
        void nightAndLegalHoliday() {
            assertThat(RATES.multiplierFor(
                    Set.of(PremiumType.NIGHT, PremiumType.LEGAL_HOLIDAY)))
                    .isEqualByComparingTo("1.600");
        }

        @Test
        @DisplayName("深夜 + 法定内残業は 1.250（法定内には割増が付かない）")
        void nightAndWithinStatutory() {
            assertThat(RATES.multiplierFor(
                    Set.of(PremiumType.NIGHT, PremiumType.OVERTIME_WITHIN_STATUTORY)))
                    .isEqualByComparingTo("1.250");
        }
    }

    @Nested
    @DisplayName("スケールの正規化")
    class Scale {

        /**
         * {@code record} の {@code equals} は {@link BigDecimal#equals} を使い、
         * 値だけでなく<strong>スケール</strong>も比較する。
         * DB の {@code NUMERIC(4,3)} は {@code 0.250} を返すので、
         * 正規化しないと定数 {@code 0.25} と等しくならない。
         */
        @Test
        @DisplayName("0.25 と 0.250 は同じ値として等しい")
        void differentScalesAreEqual() {
            var written = new PremiumRates(new BigDecimal("0.25"), new BigDecimal("0.25"),
                    new BigDecimal("0.35"));
            var readBack = new PremiumRates(new BigDecimal("0.250"), new BigDecimal("0.250"),
                    new BigDecimal("0.350"));

            assertThat(readBack).isEqualTo(written).isEqualTo(PremiumRates.STATUTORY);
            assertThat(readBack.hashCode()).isEqualTo(written.hashCode());
        }

        @Test
        @DisplayName("倍率もスケールをそろえて返す")
        void multiplierIsNormalized() {
            assertThat(RATES.multiplierFor(Set.of()).scale()).isEqualTo(3);
            assertThat(RATES.multiplierFor(Set.of(PremiumType.NIGHT)).scale()).isEqualTo(3);
        }

        /** DB のカラムに収まらない精度を、黙って丸めて受け入れない。 */
        @Test
        @DisplayName("小数第 4 位を持つ割増率は拒否する")
        void tooPreciseIsRejected() {
            assertThatThrownBy(() -> new PremiumRates(new BigDecimal("0.2501"),
                    new BigDecimal("0.25"), new BigDecimal("0.35")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("小数第 3 位までです");
        }
    }

    @Nested
    @DisplayName("法定下限")
    class Minimum {

        @Test
        @DisplayName("法定休日 0.30 は拒否される")
        void legalHolidayBelowMinimum() {
            assertThatThrownBy(() -> new PremiumRates(new BigDecimal("0.25"),
                    new BigDecimal("0.25"), new BigDecimal("0.30")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("法定休日の割増率が法定下限を下回っています");
        }

        @Test
        @DisplayName("深夜 0.20 は拒否される")
        void nightBelowMinimum() {
            assertThatThrownBy(() -> new PremiumRates(new BigDecimal("0.25"),
                    new BigDecimal("0.20"), new BigDecimal("0.35")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("深夜の割増率が法定下限を下回っています");
        }

        /** 下限は「下回らない」であって「一致する」ではない。上乗せは適法。 */
        @Test
        @DisplayName("法定を上回る割増率は受け入れる")
        void aboveMinimumIsAccepted() {
            var generous = new PremiumRates(new BigDecimal("0.30"), new BigDecimal("0.30"),
                    new BigDecimal("0.40"));

            assertThat(generous.multiplierFor(Set.of(PremiumType.LEGAL_HOLIDAY, PremiumType.NIGHT)))
                    .isEqualByComparingTo("1.700");
        }
    }
}
