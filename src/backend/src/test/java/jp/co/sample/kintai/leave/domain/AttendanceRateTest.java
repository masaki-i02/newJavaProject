package jp.co.sample.kintai.leave.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 出勤率の判定（BR-14・労基法 39 条 1 項）。 */
@DisplayName("出勤率 8 割の判定")
class AttendanceRateTest {

    /**
     * <strong>ちょうど 8 割は満たす。</strong>
     *
     * <p>ここが閾値をまたぐ 1 件である。120/150 は数学的にちょうど 8 割で、
     * {@code attendedDays * 10 >= totalWorkingDays * 8} は 1200 >= 1200 で真になる。
     */
    @Test
    @DisplayName("UT-LV-09 出勤率がちょうど 8 割なら付与する")
    void exactlyEighty() {
        assertThat(AttendanceRate.of(150, 120).meetsThreshold()).isTrue();
    }

    /** 1 日足りないだけで不付与になる。閾値の反対側。 */
    @Test
    @DisplayName("UT-LV-10 出勤率が 8 割をわずかに下回ると不付与")
    void justBelow() {
        assertThat(AttendanceRate.of(150, 119).meetsThreshold()).isFalse();
    }

    /**
     * <strong>「8 割を満たさなかった」とは言えないので満たしたものとして扱う。</strong>
     * 社員に不利な方向へ倒れないようにする。
     *
     * <p>ただしこの緩和は付与日に在籍している社員にしか当てない。
     * 退職者は在籍期間で絞ると必ず全労働日 0 になるので、
     * 付与の対象から外していないとこれを必ず通ってしまう（落とし穴 92）。
     */
    @Test
    @DisplayName("UT-LV-11 全労働日が 0 の算定期間は満たしたものとして扱う")
    void noWorkingDays() {
        assertThat(AttendanceRate.of(0, 0).meetsThreshold()).isTrue();
    }

    @Test
    @DisplayName("UT-LV-15 出勤日が全労働日を超える値では生成できない")
    void tooManyAttended() {
        assertThatThrownBy(() -> AttendanceRate.of(150, 151))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全労働日を超えています");
    }

    /**
     * <strong>休業（労災・産育休）を出勤扱いとして人事が申告する</strong>（BR-14・要件 1.1）。
     * 記録が無いままだと産休・育休の社員はその年の付与が必ず 0 になる。
     */
    @Test
    @DisplayName("UT-LV-50 出勤扱いの日数を加えると 8 割を満たす")
    void deemedAttended() {
        var withoutLeave = new AttendanceRate(245, 150, 0, null);
        assertThat(withoutLeave.meetsThreshold()).isFalse();

        var withLeave = new AttendanceRate(245, 150, 46, "産前産後休業");
        assertThat(withLeave.effectiveAttendedDays()).isEqualTo(196);
        assertThat(withLeave.meetsThreshold()).isTrue();
    }

    /**
     * <strong>和で検査する。</strong>
     * 各項目が分母以下であることだけを見る実装では、この値が通ってしまう。
     */
    @Test
    @DisplayName("UT-LV-51 各項目は全労働日以下でも、和が超えれば生成できない")
    void deemedSumExceeds() {
        assertThatThrownBy(() -> new AttendanceRate(150, 120, 40, "産前産後休業"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全労働日を超えています");
    }

    @Test
    @DisplayName("出勤扱いを申告して理由が無ければ生成できない")
    void deemedWithoutReason() {
        assertThatThrownBy(() -> new AttendanceRate(245, 150, 46, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("根拠");
    }

    /**
     * <strong>就業規則の値でなく、法定の割合そのものを検査する。</strong>
     * 分母を変えても閾値の位置が動くことを確かめ、
     * 「120 と 150 のときだけ通る」ベタ書きの実装を許さない。
     */
    @Test
    @DisplayName("分母が変わっても 8 割の位置が動く")
    void otherDenominators() {
        assertThat(AttendanceRate.of(10, 8).meetsThreshold()).isTrue();
        assertThat(AttendanceRate.of(10, 7).meetsThreshold()).isFalse();
        assertThat(AttendanceRate.of(245, 196).meetsThreshold()).isTrue();
        assertThat(AttendanceRate.of(245, 195).meetsThreshold()).isFalse();
    }
}
