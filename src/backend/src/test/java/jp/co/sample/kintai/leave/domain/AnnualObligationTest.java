package jp.co.sample.kintai.leave.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 年 5 日の取得義務（BR-17・労基法 39 条 7 項）。 */
@DisplayName("年 5 日の取得義務")
class AnnualObligationTest {

    private static final LocalDate GRANTED_ON = LocalDate.of(2026, 4, 1);

    @Test
    @DisplayName("UT-LV-38 5 日取得していれば充足")
    void fulfilled() {
        var obligation = obligation(5);

        assertThat(obligation.shortfallDays()).isZero();
        assertThat(obligation.isFulfilled()).isTrue();
    }

    /** 閾値の反対側。4 日では足りない。 */
    @Test
    @DisplayName("UT-LV-39 3 日なら不足 2 日")
    void shortfall() {
        assertThat(obligation(3).shortfallDays()).isEqualTo(2);
        assertThat(obligation(4).shortfallDays()).isEqualTo(1);
        assertThat(obligation(4).isFulfilled()).isFalse();
    }

    /**
     * <strong>期間は付与日から 1 年。</strong> 暦年でも年度でもなく、社員ごとに違う。
     * 半開区間なので、付与日 + 1 年の当日は数えない。
     */
    @Test
    @DisplayName("UT-LV-40 期間は付与日から 1 年（半開区間）")
    void period() {
        var obligation = obligation(0);

        assertThat(obligation.period().from()).isEqualTo(GRANTED_ON);
        assertThat(obligation.period().toExclusive()).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(obligation.period().contains(LocalDate.of(2027, 3, 31))).isTrue();
        assertThat(obligation.period().contains(LocalDate.of(2027, 4, 1))).isFalse();
    }

    /** 利用者に示す期限日は閉区間の最終日なので、区間の上限とは 1 日ずれる。 */
    @Test
    @DisplayName("期限日は付与日 + 1 年 − 1 日")
    void deadline() {
        assertThat(obligation(0).deadline()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    /** 5 日を超えて取得しても不足は負にならない。 */
    @Test
    @DisplayName("UT-LV-43 5 日を超えて取得しても不足は 0")
    void moreThanRequired() {
        assertThat(obligation(20).shortfallDays()).isZero();
    }

    private static AnnualObligation obligation(int takenDays) {
        return new AnnualObligation(PaidLeaveGrantId.generate(), GRANTED_ON, takenDays);
    }
}
