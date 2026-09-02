package jp.co.sample.kintai.attendance.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.attendance.domain.TimeClockEvent.BreakEnd;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent.BreakStart;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent.ClockIn;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent.ClockOut;
import jp.co.sample.kintai.shared.domain.TimeRange;

/**
 * 1 勤務日分の有効な打刻の並び。状態機械として畳み込む（BR-02）。
 *
 * <p>検査と変換を分けているのは、打刻を受け付ける時点ではまだ勤務が終わっていないため。
 * 「出勤打刻を 2 回した」は即座に拒否したいが、「まだ退勤していない」は正常な状態である。
 */
public record TimeClockSequence(List<TimeClockEvent> events) {

    /** 状態機械の状態。 */
    enum Status {
        NOT_STARTED, WORKING, ON_BREAK, FINISHED
    }

    /** 畳み込みの結果。状態と区間を 1 度の走査で得る。 */
    private record Folded(Status status, List<TimeRange> ranges, Optional<TimeRange> span) {
    }

    public TimeClockSequence {
        if (events == null) {
            throw new IllegalArgumentException("打刻の並びに null は許されません");
        }
        // 同時刻の打刻が並び順で結果を変えないよう、種別にも順序を与える
        events = events.stream()
                .sorted(Comparator.comparing(TimeClockEvent::occurredAt)
                        .thenComparingInt(TimeClockSequence::orderWithinSameInstant))
                .toList();
    }

    /** 同時刻なら「労働を終える打刻 → 労働を始める打刻」の順に扱う。 */
    private static int orderWithinSameInstant(TimeClockEvent event) {
        return switch (event) {
            case ClockIn ignored -> 0;
            case BreakStart ignored -> 1;
            case BreakEnd ignored -> 2;
            case ClockOut ignored -> 3;
        };
    }

    public static TimeClockSequence of(List<TimeClockEvent> events) {
        return new TimeClockSequence(events);
    }

    public static TimeClockSequence empty() {
        return new TimeClockSequence(List.of());
    }

    /** 遷移の妥当性だけを検査する。<strong>退勤前の途中状態も許容する。</strong> */
    public void validateTransitions() {
        fold();
    }

    /**
     * 退勤まで完了しているか。日次勤怠を計算できるかの判定。
     *
     * <p><strong>遷移が不正な列に対しては false を返す。</strong>
     * 状態機械を 2 つ持って「計算できる」と答えた列で例外が飛ぶ、という契約違反を避ける。
     */
    public boolean isClosed() {
        try {
            return fold().status() == Status.FINISHED;
        } catch (InvalidTimeClockSequenceException e) {
            return false;
        }
    }

    /** 打刻が 1 件も無いか。休日や欠勤で正常に起こりうる。 */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * 実労働区間へ変換する。休憩で挟まれた区間は除かれる。
     *
     * <p><strong>未完了なら例外。</strong> 労働時間が確定しないため。
     */
    public List<TimeRange> toWorkedRanges() {
        Folded folded = fold();
        requireFinished(folded.status());
        return folded.ranges();
    }

    /**
     * 拘束時間（出勤から退勤まで）。
     *
     * <p><strong>実労働区間からは求められない。</strong>
     * 出勤直後に休憩を取ると先頭の区間が長さ 0 になって捨てられ、
     * 出勤打刻の時刻が失われる。休憩時間はここから実労働を引いて求める。
     */
    public Optional<TimeRange> attendanceSpan() {
        Folded folded = fold();
        requireFinished(folded.status());
        return folded.span();
    }

    private void requireFinished(Status status) {
        if (status != Status.FINISHED && !events.isEmpty()) {
            throw new IncompleteTimeClockSequenceException(
                    "退勤打刻がないため労働時間を確定できません（状態: %s）".formatted(describe(status)));
        }
    }

    /**
     * 打刻列を 1 度だけ走査して、状態・実労働区間・拘束時間を求める。
     *
     * <p>状態機械をここ 1 か所に閉じる。2 か所に持つと、
     * 片方が「計算できる」と答えた列でもう片方が例外を投げる、という食い違いが起きる。
     */
    private Folded fold() {
        List<TimeRange> ranges = new ArrayList<>();
        Status status = Status.NOT_STARTED;
        LocalDateTime segmentStart = null;
        LocalDateTime clockedInAt = null;
        LocalDateTime clockedOutAt = null;

        for (TimeClockEvent event : events) {
            switch (event) {
                case ClockIn in -> {
                    requireStatus(status, Status.NOT_STARTED, "出勤", in.occurredAt());
                    status = Status.WORKING;
                    // 労働の開始側は秒を切り捨てる。時刻が早くなり、労働時間は長くなる（BR-01）
                    clockedInAt = TimeRange.floorToMinute(in.occurredAt());
                    segmentStart = clockedInAt;
                }
                case BreakStart bs -> {
                    requireStatus(status, Status.WORKING, "休憩開始", bs.occurredAt());
                    // 労働の終了側は秒を切り上げる（BR-01）
                    addRange(ranges, segmentStart, TimeRange.ceilToMinute(bs.occurredAt()));
                    status = Status.ON_BREAK;
                    segmentStart = null;
                }
                case BreakEnd be -> {
                    requireStatus(status, Status.ON_BREAK, "休憩終了", be.occurredAt());
                    status = Status.WORKING;
                    segmentStart = clampToPrevious(ranges,
                            TimeRange.floorToMinute(be.occurredAt()));
                }
                case ClockOut out -> {
                    requireStatus(status, Status.WORKING, "退勤", out.occurredAt());
                    clockedOutAt = TimeRange.ceilToMinute(out.occurredAt());
                    addRange(ranges, segmentStart, clockedOutAt);
                    status = Status.FINISHED;
                    segmentStart = null;
                }
            }
            // default 句を書かない。打刻種別を追加した瞬間にここがコンパイルエラーになる
        }

        Optional<TimeRange> span = (clockedInAt != null && clockedOutAt != null)
                ? Optional.of(new TimeRange(clockedInAt, clockedOutAt))
                : Optional.empty();
        return new Folded(status, List.copyOf(ranges), span);
    }

    /**
     * 直前の区間の終わりより前には戻さない。
     *
     * <p>秒の丸めは開始を早め、終了を遅らせる。<strong>1 分未満の休憩では
     * 「休憩開始（切り上げ）＞ 休憩終了（切り捨て）」となり、区間が重なる。</strong>
     * 重なった分は両方の区間に計上され、実労働が拘束時間を超え、休憩が負になる。
     *
     * <p>クランプすると、その休憩は長さ 0 として扱われる。
     * 労働時間が短くなる方向へは動かないので、BR-01 の向きは保たれる。
     */
    private static LocalDateTime clampToPrevious(List<TimeRange> ranges,
                                                 LocalDateTime candidate) {
        if (ranges.isEmpty()) {
            return candidate;
        }
        LocalDateTime previousEnd = ranges.get(ranges.size() - 1).end();
        return candidate.isBefore(previousEnd) ? previousEnd : candidate;
    }

    private static void requireStatus(Status actual, Status expected,
                                      String label, LocalDateTime at) {
        if (actual != expected) {
            throw new InvalidTimeClockSequenceException(
                    "%s の打刻(%s) は %s の状態では行えません".formatted(label, at, describe(actual)));
        }
    }

    private static String describe(Status status) {
        return switch (status) {
            case NOT_STARTED -> "未出勤";
            case WORKING -> "勤務中";
            case ON_BREAK -> "休憩中";
            case FINISHED -> "退勤済";
        };
    }

    /** 長さ 0 の区間は記録しない。労働実績として意味を持たないため。 */
    private static void addRange(List<TimeRange> ranges, LocalDateTime start,
                                 LocalDateTime end) {
        if (start != null && start.isBefore(end)) {
            ranges.add(new TimeRange(start, end));
        }
    }
}
