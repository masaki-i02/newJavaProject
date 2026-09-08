package jp.co.sample.kintai.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.PremiumType;
import jp.co.sample.kintai.shared.domain.TimeRange;

/** 割増属性が確定した労働区間。 */
@DisplayName("WorkSlice（労働区間の最小単位）")
class WorkSliceTest {

    private static final TimeRange RANGE = new TimeRange(
            LocalDateTime.parse("2026-04-06T09:00"), LocalDateTime.parse("2026-04-06T10:00"));

    /**
     * 労働時間を<strong>分割する</strong>区分は排他である。
     * 2 つ付くと、内訳の合計が実労働時間を超える。
     */
    @Test
    @DisplayName("排他的な区分は 1 区間に 2 つ付けられない")
    void twoExclusivePremiumsAreRejected() {
        assertThatThrownBy(() -> new WorkSlice(RANGE, Set.of(
                PremiumType.LEGAL_HOLIDAY, PremiumType.OVERTIME_BEYOND_STATUTORY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("排他的な割増区分が 1 区間に 2 つ以上");
    }

    @Test
    @DisplayName("排他的な区分は 1 つなら付けられる")
    void oneExclusivePremiumIsAllowed() {
        assertThat(new WorkSlice(RANGE, Set.of(PremiumType.OVERTIME_BEYOND_STATUTORY)).premiums())
                .containsExactly(PremiumType.OVERTIME_BEYOND_STATUTORY);
    }

    /**
     * <strong>深夜は他の区分に重ねて付く属性であり、労働時間を分割する区分ではない。</strong>
     * ここを排他にすると、深夜の法定外残業をどちらか一方でしか数えられなくなる。
     */
    @Test
    @DisplayName("深夜は排他的な区分に重ねて付けられる")
    void nightOverlaysAnExclusivePremium() {
        var slice = new WorkSlice(RANGE,
                Set.of(PremiumType.OVERTIME_BEYOND_STATUTORY, PremiumType.NIGHT));

        assertThat(slice.premiums())
                .containsExactlyInAnyOrder(PremiumType.OVERTIME_BEYOND_STATUTORY,
                        PremiumType.NIGHT);
    }

    @Test
    @DisplayName("属性の集合は外から書き換えられない")
    void premiumsAreImmutable() {
        var slice = new WorkSlice(RANGE, Set.of(PremiumType.NIGHT));

        assertThatThrownBy(() -> slice.premiums().add(PremiumType.LEGAL_HOLIDAY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("with は元の区間を変えず、同じ属性なら自分を返す")
    void withDoesNotMutate() {
        var slice = new WorkSlice(RANGE, Set.of(PremiumType.NIGHT));

        assertThat(slice.with(PremiumType.NIGHT)).isSameAs(slice);
        assertThat(slice.with(PremiumType.LEGAL_HOLIDAY).premiums()).hasSize(2);
        assertThat(slice.premiums()).containsExactly(PremiumType.NIGHT);
    }

    /**
     * <strong>またぐ区間に暦日は定まらない。</strong>
     * 開始日を黙って返すと、土曜 22:00〜日曜 6:00 の 8 時間がまるごと土曜のものになり、
     * 日曜（法定休日）の 35% が付かない。
     */
    @Test
    @DisplayName("UT-ATT-42 暦日をまたぐ区間は暦日を答えない")
    void crossingSliceHasNoCalendarDate() {
        var crossing = WorkSlice.plain(new TimeRange(
                LocalDateTime.parse("2026-04-04T22:00"),
                LocalDateTime.parse("2026-04-05T06:00")));

        assertThat(crossing.crossesCalendarDay()).isTrue();
        assertThatThrownBy(crossing::calendarDate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("暦日をまたぐ区間には暦日が定まりません");
    }

    /**
     * 半開区間なので<strong>翌日 0:00 ちょうどで終わる区間はまたいでいない。</strong>
     * 「終了日が違えばまたぎ」と書くと、暦日境界で分割した前半をまたぎと誤判定し、
     * 分割した直後の区間をどれも扱えなくなる。
     */
    @Test
    @DisplayName("UT-ATT-43 翌日 0:00 ちょうどで終わる区間はまたいでいない")
    void sliceEndingAtMidnightDoesNotCross() {
        var upToMidnight = WorkSlice.plain(new TimeRange(
                LocalDateTime.parse("2026-04-04T22:00"),
                LocalDateTime.parse("2026-04-05T00:00")));

        assertThat(upToMidnight.crossesCalendarDay()).isFalse();
        assertThat(upToMidnight.calendarDate())
                .isEqualTo(java.time.LocalDate.of(2026, 4, 4));
    }

    @Test
    @DisplayName("分割しても属性を引き継ぎ、合計は変わらない")
    void splitKeepsPremiumsAndTotal() {
        var slice = new WorkSlice(RANGE, Set.of(PremiumType.NIGHT));

        var parts = slice.splitAt(LocalDateTime.parse("2026-04-06T09:30"));

        assertThat(parts).hasSize(2)
                .allSatisfy(part -> assertThat(part.has(PremiumType.NIGHT)).isTrue());
        assertThat(parts.get(0).duration().plus(parts.get(1).duration()))
                .isEqualTo(slice.duration());
    }
}
