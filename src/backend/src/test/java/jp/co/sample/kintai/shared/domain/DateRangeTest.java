package jp.co.sample.kintai.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日付の半開区間。
 *
 * <p>在籍期間（閉区間の感覚）と所属期間（半開区間）が混ざると
 * <strong>退職日当日の 1 日が消える</strong>（CLAUDE.md 落とし穴 10）。
 */
@DisplayName("DateRange（日付の半開区間）")
class DateRangeTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_20 = LocalDate.of(2026, 9, 20);

    /** 退職日は<strong>最終在籍日</strong>なので、上限は翌日になる。 */
    @Test
    @DisplayName("最終日を含む形から作ると、上限は翌日になる")
    void closedAddsOneDay() {
        var period = DateRange.closed(SEP_1, SEP_20);

        assertThat(period.toExclusive()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(period.contains(SEP_20)).isTrue();
        assertThat(period.contains(LocalDate.of(2026, 9, 21))).isFalse();
        assertThat(period.days()).isEqualTo(20);
    }

    @Test
    @DisplayName("上限のない期間は番兵を持ち、日数を聞くと例外になる")
    void unboundedHasNoDayCount() {
        var period = DateRange.startingAt(SEP_1);

        assertThat(period.isUnbounded()).isTrue();
        assertThat(period.toExclusive()).isEqualTo(DateRange.UNBOUNDED_END);
        assertThatThrownBy(period::days)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("端の無い期間の日数は求められません");
    }

    /**
     * 番兵を「最終日」として渡されると {@code plusDays(1)} が桁あふれする。
     * 例外の型もメッセージも実装の都合が漏れたものになるので、手前で弾く。
     */
    @Test
    @DisplayName("最終日に番兵は渡せない")
    void closedRejectsTheSentinel() {
        assertThatThrownBy(() -> DateRange.closed(SEP_1, DateRange.UNBOUNDED_END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最終日に番兵を渡さないでください");
    }

    @Test
    @DisplayName("接しているだけの期間は重ならない")
    void touchingPeriodsDoNotOverlap() {
        var first = new DateRange(SEP_1, SEP_20);
        var second = new DateRange(SEP_20, LocalDate.of(2026, 10, 1));

        assertThat(first.overlaps(second)).isFalse();
        assertThat(first.intersect(second)).isEmpty();
    }

    /**
     * 重なりの判定。
     *
     * <p>「重ならない」ケースしか無いと、{@code return false;} に潰しても通ってしまう。
     * <strong>真になるケースを必ず置く。</strong>
     */
    @Test
    @DisplayName("一部でも重なれば true、包含も true")
    void overlappingPeriods() {
        var september = new DateRange(SEP_1, LocalDate.of(2026, 10, 1));

        assertThat(september.overlaps(new DateRange(SEP_20, LocalDate.of(2026, 10, 10))))
                .as("後ろで重なる").isTrue();
        assertThat(september.overlaps(new DateRange(LocalDate.of(2026, 8, 20), SEP_20)))
                .as("前で重なる").isTrue();
        assertThat(september.overlaps(new DateRange(SEP_1, SEP_20)))
                .as("内側に含む").isTrue();
        assertThat(september.overlaps(new DateRange(LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1)))).as("外側に含まれる").isTrue();
        assertThat(september.overlaps(new DateRange(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 11, 1)))).as("接しているだけ").isFalse();

        assertThat(september.intersect(new DateRange(SEP_20, LocalDate.of(2026, 10, 10))))
                .contains(new DateRange(SEP_20, LocalDate.of(2026, 10, 1)));
    }

    @Test
    @DisplayName("判定する日付に null は渡せない")
    void containsRejectsNull() {
        assertThatThrownBy(() -> new DateRange(SEP_1, SEP_20).contains(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("判定する日付に null は許されません");
    }

    @Test
    @DisplayName("長さ 0 の期間は作れない")
    void emptyPeriodIsRejected() {
        assertThatThrownBy(() -> new DateRange(SEP_1, SEP_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("開始は終了より前");
    }
}
