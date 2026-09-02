package jp.co.sample.kintai.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 労基法 34 条の休憩（BR-08）。
 *
 * <p>条文は「6 時間を<strong>超える</strong>場合は 45 分、
 * 8 時間を<strong>超える</strong>場合は 60 分」である。
 * ちょうど 6 時間・8 時間は下の段に入る。
 * {@code >} を {@code >=} と書き違えても、境界を試さなければテストは通ってしまう。
 */
@DisplayName("休憩時間の要件（BR-08）")
class BreakTimeRequirementTest {

    private static Duration requiredFor(Duration workingTime) {
        return new BreakTimeRequirement(workingTime, Duration.ZERO).required();
    }

    @Test
    @DisplayName("6 時間ちょうどは休憩が要らない")
    void exactlySixHoursNeedsNoBreak() {
        assertThat(requiredFor(Duration.ofHours(6))).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("6 時間を 1 分でも超えると 45 分が要る")
    void justOverSixHoursNeeds45Minutes() {
        assertThat(requiredFor(Duration.ofHours(6).plusMinutes(1)))
                .isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    @DisplayName("8 時間ちょうどは 45 分でよい")
    void exactlyEightHoursNeeds45Minutes() {
        assertThat(requiredFor(Duration.ofHours(8))).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    @DisplayName("8 時間を 1 分でも超えると 60 分が要る")
    void justOverEightHoursNeeds60Minutes() {
        assertThat(requiredFor(Duration.ofHours(8).plusMinutes(1)))
                .isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("休憩が要件どおりなら満たしている")
    void exactBreakIsSatisfied() {
        assertThat(new BreakTimeRequirement(Duration.ofHours(7), Duration.ofMinutes(45))
                .isSatisfied()).isTrue();
    }

    /**
     * <strong>不足していても計算は打刻どおりに行う。</strong> 判定を返すだけである。
     * 勝手に休憩を差し引くと、実際には働いていた時間が消えて賃金の不払いになる。
     */
    @Test
    @DisplayName("1 分でも足りなければ満たしていない")
    void oneMinuteShortIsNotSatisfied() {
        assertThat(new BreakTimeRequirement(Duration.ofHours(7), Duration.ofMinutes(44))
                .isSatisfied()).isFalse();
    }
}
