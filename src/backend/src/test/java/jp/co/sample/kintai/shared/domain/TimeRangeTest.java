package jp.co.sample.kintai.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 日時の半開区間。
 *
 * <p>ここが崩れると、上位のすべての集計が静かに狂う。
 * <strong>不変条件そのものを直接検査する。</strong>
 * 上位のテストが通っていることは、この型が正しい証拠にならない。
 */
@DisplayName("TimeRange（日時の半開区間）")
class TimeRangeTest {

    private static LocalDateTime at(String text) {
        return LocalDateTime.parse(text);
    }

    @Nested
    @DisplayName("分精度の強制")
    class MinutePrecision {

        /**
         * 秒を含んだまま区間を細切れにすると、分割後の合計が分割前と一致しなくなり、
         * <strong>「内訳の合計 = 実労働時間」という不変条件が壊れる</strong>（BR-01）。
         */
        @Test
        @DisplayName("開始に秒があると生成できない")
        void secondsInStartAreRejected() {
            assertThatThrownBy(() -> new TimeRange(at("2026-04-06T09:00:30"),
                    at("2026-04-06T18:00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("開始は分精度である必要があります");
        }

        @Test
        @DisplayName("終了に秒があると生成できない")
        void secondsInEndAreRejected() {
            assertThatThrownBy(() -> new TimeRange(at("2026-04-06T09:00"),
                    at("2026-04-06T18:00:01")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("終了は分精度である必要があります");
        }

        @Test
        @DisplayName("ナノ秒だけでも生成できない")
        void nanosAreRejected() {
            assertThatThrownBy(() -> new TimeRange(at("2026-04-06T09:00:00.000000001"),
                    at("2026-04-06T18:00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("分精度");
        }

        @Test
        @DisplayName("長さ 0 の区間は作れない")
        void emptyRangeIsRejected() {
            assertThatThrownBy(() -> new TimeRange(at("2026-04-06T09:00"),
                    at("2026-04-06T09:00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("開始は終了より前");
        }

        @Test
        @DisplayName("逆順の区間は作れない")
        void reversedRangeIsRejected() {
            assertThatThrownBy(() -> new TimeRange(at("2026-04-06T18:00"),
                    at("2026-04-06T09:00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("開始は終了より前");
        }
    }

    @Nested
    @DisplayName("秒の丸め（BR-01）")
    class Rounding {

        /** 労働の開始側は切り捨てる。労働時間が<strong>長くなる</strong>側。 */
        @Test
        @DisplayName("開始は切り捨てる")
        void floorMovesEarlier() {
            assertThat(TimeRange.floorToMinute(at("2026-04-06T09:00:59")))
                    .isEqualTo(at("2026-04-06T09:00"));
        }

        /** 労働の終了側は切り上げる。同じく労働時間が長くなる側。 */
        @Test
        @DisplayName("終了は切り上げる")
        void ceilMovesLater() {
            assertThat(TimeRange.ceilToMinute(at("2026-04-06T18:00:01")))
                    .isEqualTo(at("2026-04-06T18:01"));
        }

        @Test
        @DisplayName("ちょうど分の時刻は切り上げても動かない")
        void ceilIsIdempotentOnExactMinutes() {
            assertThat(TimeRange.ceilToMinute(at("2026-04-06T18:00")))
                    .isEqualTo(at("2026-04-06T18:00"));
        }
    }

    @Nested
    @DisplayName("半開区間の境界")
    class Boundaries {

        @Test
        @DisplayName("開始は含み、終了は含まない")
        void containsIsHalfOpen() {
            var range = new TimeRange(at("2026-04-06T09:00"), at("2026-04-06T18:00"));

            assertThat(range.contains(at("2026-04-06T09:00"))).isTrue();
            assertThat(range.contains(at("2026-04-06T17:59"))).isTrue();
            assertThat(range.contains(at("2026-04-06T18:00"))).isFalse();
            assertThat(range.contains(at("2026-04-06T08:59"))).isFalse();
        }

        /** 端点で切ると長さ 0 の区間ができる。<strong>作らない。</strong> */
        @Test
        @DisplayName("端点では分割しない")
        void splitAtEndpointsDoesNothing() {
            var range = new TimeRange(at("2026-04-06T09:00"), at("2026-04-06T18:00"));

            assertThat(range.splitAt(at("2026-04-06T09:00"))).containsExactly(range);
            assertThat(range.splitAt(at("2026-04-06T18:00"))).containsExactly(range);
            assertThat(range.splitAt(at("2026-04-06T20:00"))).containsExactly(range);
        }

        @Test
        @DisplayName("内側で分割すると合計は変わらない")
        void splitPreservesTotal() {
            var range = new TimeRange(at("2026-04-06T09:00"), at("2026-04-06T18:00"));

            var parts = range.splitAt(at("2026-04-06T12:00"));

            assertThat(parts).hasSize(2);
            assertThat(parts.get(0).duration().plus(parts.get(1).duration()))
                    .isEqualTo(range.duration());
            assertThat(parts.get(0).end()).isEqualTo(parts.get(1).start());
        }

        @Test
        @DisplayName("接しているだけの区間は交差しない")
        void touchingRangesDoNotIntersect() {
            var morning = new TimeRange(at("2026-04-06T09:00"), at("2026-04-06T12:00"));
            var afternoon = new TimeRange(at("2026-04-06T12:00"), at("2026-04-06T18:00"));

            assertThat(morning.intersect(afternoon)).isEmpty();
        }
    }
}
