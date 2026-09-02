package jp.co.sample.kintai.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.Punches;

/**
 * 打刻の状態機械（BR-02）を、日次集計を通さずに直接検査する。
 *
 * <p>集計側のテストからでは「例外になった」ことしか見えない。
 * <strong>検査（{@code validateTransitions}）と判定（{@code isClosed}）は
 * 打刻を受け付ける画面が直接使う</strong>ので、その振る舞いを単体で押さえる。
 */
@DisplayName("打刻の状態機械（BR-02）")
class TimeClockSequenceTest {

    @Nested
    @DisplayName("遷移の検査")
    class Transitions {

        /** 打刻を受け付ける時点で即座に拒否したい誤り。 */
        @Test
        @DisplayName("出勤打刻を 2 回すると拒否される")
        void doubleClockIn() {
            assertThatThrownBy(() ->
                    Punches.on("2026-04-06").in("09:00").in("09:30").build()
                            .validateTransitions())
                    .isInstanceOf(InvalidTimeClockSequenceException.class)
                    .hasMessageContaining("出勤 の打刻")
                    .hasMessageContaining("勤務中 の状態では行えません");
        }

        @Test
        @DisplayName("出勤前の休憩開始は拒否される")
        void breakBeforeClockIn() {
            assertThatThrownBy(() ->
                    Punches.on("2026-04-06").breakFrom("09:00").build().validateTransitions())
                    .isInstanceOf(InvalidTimeClockSequenceException.class)
                    .hasMessageContaining("未出勤");
        }

        @Test
        @DisplayName("休憩中の退勤は拒否される")
        void clockOutDuringBreak() {
            assertThatThrownBy(() ->
                    Punches.on("2026-04-06").in("09:00").breakFrom("12:00").out("18:00")
                            .build().validateTransitions())
                    .isInstanceOf(InvalidTimeClockSequenceException.class)
                    .hasMessageContaining("休憩中");
        }

        /**
         * 「まだ退勤していない」は<strong>正常な途中の状態</strong>である。
         * ここを拒否すると、勤務中の社員が休憩の打刻を打てなくなる。
         */
        @Test
        @DisplayName("退勤前の途中状態は拒否されない")
        void incompleteSequenceIsValid() {
            assertThatCode(() ->
                    Punches.on("2026-04-06").in("09:00").breakFrom("12:00").build()
                            .validateTransitions())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("打刻が 1 件も無い日も拒否されない（欠勤）")
        void emptySequenceIsValid() {
            assertThatCode(() -> TimeClockSequence.empty().validateTransitions())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("退勤済みかの判定")
    class Closed {

        @Test
        @DisplayName("退勤まで打刻されていれば true")
        void finishedIsClosed() {
            assertThat(Punches.on("2026-04-06").in("09:00").out("18:00").build().isClosed())
                    .isTrue();
        }

        @Test
        @DisplayName("退勤していなければ false")
        void workingIsNotClosed() {
            assertThat(Punches.on("2026-04-06").in("09:00").build().isClosed()).isFalse();
        }

        @Test
        @DisplayName("打刻が無ければ false")
        void emptyIsNotClosed() {
            assertThat(TimeClockSequence.empty().isClosed()).isFalse();
        }

        /**
         * 並びが壊れている列に対して「退勤済み」と答えてはいけない。
         * <strong>例外を投げるのでもいけない。</strong>
         * 呼び出し側は「計算してよいか」を聞いているだけなので、答えは false である。
         */
        @Test
        @DisplayName("並びが壊れていても例外にせず false を返す")
        void invalidSequenceIsNotClosed() {
            var broken = Punches.on("2026-04-06").in("09:00").in("09:30").out("18:00").build();

            assertThat(broken.isClosed()).isFalse();
        }
    }

    @Nested
    @DisplayName("打刻が無い日")
    class Empty {

        /**
         * 欠勤日に対して呼んでも例外にしない。
         *
         * <p>{@code DailyAttendanceCalculator} は先に {@code isEmpty()} で短絡するので、
         * 集計経由のテストではこの経路に到達しない。
         * 画面や月次集計が直接呼ぶ経路があるため、契約を単体で押さえる。
         */
        @Test
        @DisplayName("実労働区間は空になる")
        void workedRangesAreEmpty() {
            assertThat(TimeClockSequence.empty().toWorkedRanges()).isEmpty();
        }

        @Test
        @DisplayName("拘束時間は空になる")
        void attendanceSpanIsEmpty() {
            assertThat(TimeClockSequence.empty().attendanceSpan()).isEmpty();
        }

        @Test
        @DisplayName("出勤打刻の時刻も空になる")
        void clockedInAtIsEmpty() {
            assertThat(TimeClockSequence.empty().clockedInAt()).isEmpty();
        }
    }

    @Nested
    @DisplayName("出勤と退勤が同一時刻")
    class ZeroLengthAttendance {

        /**
         * <strong>レビューで見つかった実バグ。</strong>
         *
         * <p>丸めた出勤と退勤が同値になると、拘束時間として長さ 0 の
         * {@code TimeRange} を作ろうとして {@code IllegalArgumentException} が飛んでいた。
         * これは {@code DomainException} ではないので Problem Details に変換されず
         * 500 になり、<strong>退勤打刻の登録そのものが落ちて一次証拠が残らない</strong>
         * （CLAUDE.md 落とし穴 19）。
         */
        @Test
        @DisplayName("例外にせず、拘束時間 0 分として扱う")
        void zeroLengthAttendanceIsNotAnError() {
            var sequence = Punches.on("2026-04-06").in("09:00").out("09:00").build();

            assertThat(sequence.isClosed()).isTrue();
            assertThat(sequence.toWorkedRanges()).isEmpty();
            assertThat(sequence.attendanceSpan()).isEmpty();
            assertThat(sequence.clockedInAt())
                    .contains(LocalDateTime.parse("2026-04-06T09:00"));
        }

        @Test
        @DisplayName("遷移の検査も通る")
        void validateTransitionsAccepts() {
            assertThatCode(() ->
                    Punches.on("2026-04-06").in("09:00").out("09:00").build()
                            .validateTransitions())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("入力順への非依存")
    class Ordering {

        /**
         * 打刻は端末から届く順が保証されない。
         * <strong>並べ替えても同じ結果になる</strong>ことを型が保証する。
         */
        @Test
        @DisplayName("打刻を逆順に渡しても同じ区間になる")
        void reversedInputYieldsTheSameRanges() {
            var forward = Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("13:00").out("18:00").build();
            var shuffled = TimeClockSequence.of(List.of(
                    new TimeClockEvent.ClockOut(at("18:00")),
                    new TimeClockEvent.BreakEnd(at("13:00")),
                    new TimeClockEvent.BreakStart(at("12:00")),
                    new TimeClockEvent.ClockIn(at("09:00"))));

            assertThat(shuffled.toWorkedRanges()).isEqualTo(forward.toWorkedRanges());
            assertThat(shuffled.attendanceSpan()).isEqualTo(forward.attendanceSpan());
        }

        /**
         * 同時刻の打刻は「労働を終える打刻 → 労働を始める打刻」の順に扱う。
         * ここが入力順まかせだと、休憩 0 分の日が長さ 0 の区間を作ったり作らなかったりする。
         */
        @Test
        @DisplayName("同時刻の休憩終了と休憩開始でも結果が一意に決まる")
        void sameInstantIsDeterministic() {
            var sequence = TimeClockSequence.of(List.of(
                    new TimeClockEvent.ClockIn(at("09:00")),
                    new TimeClockEvent.BreakEnd(at("12:00")),
                    new TimeClockEvent.BreakStart(at("12:00")),
                    new TimeClockEvent.ClockOut(at("18:00"))));

            assertThat(sequence.events()).extracting(Object::getClass)
                    .containsExactly(TimeClockEvent.ClockIn.class,
                            TimeClockEvent.BreakStart.class,
                            TimeClockEvent.BreakEnd.class,
                            TimeClockEvent.ClockOut.class);
        }

        /**
         * 休憩を 2 つに割った境界が同一時刻でも受け入れる。
         *
         * <p>種別に固定の全順序（休憩開始 → 休憩終了）を与えると、
         * この列は「休憩開始・休憩開始・休憩終了・休憩終了」に並べ替えられ、
         * <strong>正当な打刻が拒否される。</strong>
         * 拒否すると働いた事実が残らない（CLAUDE.md 落とし穴 19）。
         */
        @Test
        @DisplayName("休憩終了と休憩開始が同一時刻でも受け入れる")
        void touchingBreaksAreAccepted() {
            var sequence = Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("12:45")
                    .breakFrom("12:45").breakTo("13:00").out("18:00").build();

            assertThatCode(sequence::validateTransitions).doesNotThrowAnyException();
            assertThat(sequence.toWorkedRanges()).hasSize(2);
            assertThat(sequence.attendanceSpan().orElseThrow().duration())
                    .isEqualTo(java.time.Duration.ofHours(9));
        }

        /**
         * 長さ 0 の休憩（休憩開始と休憩終了が同一時刻）も受け入れる。
         *
         * <p>区間は 2 本に分かれるが、<strong>接しているだけで重ならない。</strong>
         * 労働時間は 9 時間のままで、休憩は 0 分である。
         */
        @Test
        @DisplayName("長さ 0 の休憩も受け入れる")
        void zeroLengthBreakIsAccepted() {
            var sequence = Punches.on("2026-04-06")
                    .in("09:00").breakFrom("12:00").breakTo("12:00").out("18:00").build();

            assertThatCode(sequence::validateTransitions).doesNotThrowAnyException();
            var worked = sequence.toWorkedRanges();
            assertThat(worked).hasSize(2);
            assertThat(worked.get(0).end())
                    .as("接しているだけで重ならない")
                    .isEqualTo(worked.get(1).start());
            assertThat(worked.stream()
                    .map(jp.co.sample.kintai.shared.domain.TimeRange::duration)
                    .reduce(java.time.Duration.ZERO, java.time.Duration::plus))
                    .isEqualTo(java.time.Duration.ofHours(9));
        }

        private LocalDateTime at(String time) {
            return LocalDateTime.parse("2026-04-06T" + time);
        }
    }
}
