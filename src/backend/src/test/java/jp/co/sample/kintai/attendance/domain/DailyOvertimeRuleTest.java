package jp.co.sample.kintai.attendance.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 残業判定の基準そのものの検証。
 *
 * <p>所定が法定を超える規則を作れてしまうと、法定内残業の区間が
 * <strong>負の長さ</strong>になる。集計側では気づけない。
 */
@DisplayName("DailyOvertimeRule（残業判定の基準）")
class DailyOvertimeRuleTest {

    @Test
    @DisplayName("所定が法定を超える基準は作れない")
    void scheduledAboveStatutoryIsRejected() {
        assertThatThrownBy(() ->
                new DailyOvertimeRule(Duration.ofHours(9), Duration.ofHours(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("所定労働時間が法定労働時間を超えています");
    }

    @Test
    @DisplayName("所定と法定が同じ基準は作れる（法定内残業が生じないだけ）")
    void scheduledEqualToStatutoryIsAllowed() {
        assertThatCode(() -> new DailyOvertimeRule(Duration.ofHours(8), Duration.ofHours(8)))
                .doesNotThrowAnyException();
    }

    /** 所定休日・法定休日は所定 0 として扱う（BR-07）。 */
    @Test
    @DisplayName("所定 0 の基準は作れる")
    void zeroScheduledIsAllowed() {
        assertThatCode(() -> new DailyOvertimeRule(Duration.ZERO, Duration.ofHours(8)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("負の基準は作れない")
    void negativeIsRejected() {
        assertThatThrownBy(() ->
                new DailyOvertimeRule(Duration.ofHours(-1), Duration.ofHours(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("負にはできません");
    }

    @Test
    @DisplayName("基準に null は許されない")
    void nullIsRejected() {
        assertThatThrownBy(() -> new DailyOvertimeRule(null, Duration.ofHours(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null は許されません");
    }
}
