package jp.co.sample.kintai.attendance.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private enum Status {
        NOT_STARTED, WORKING, ON_BREAK, FINISHED
    }

    public TimeClockSequence {
        if (events == null) {
            throw new IllegalArgumentException("打刻の並びに null は許されません");
        }
        events = events.stream()
                .sorted(Comparator.comparing(TimeClockEvent::occurredAt))
                .toList();
    }

    public static TimeClockSequence of(List<TimeClockEvent> events) {
        return new TimeClockSequence(events);
    }

    public static TimeClockSequence empty() {
        return new TimeClockSequence(List.of());
    }

    /** 遷移の妥当性だけを検査する。<strong>退勤前の途中状態も許容する。</strong> */
    public void validateTransitions() {
        fold(false);
    }

    /** 退勤まで完了しているか。日次勤怠を計算できるかの判定。 */
    public boolean isClosed() {
        return finalStatus() == Status.FINISHED;
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
        return fold(true);
    }

    private Status finalStatus() {
        Status status = Status.NOT_STARTED;
        for (TimeClockEvent event : events) {
            status = switch (event) {
                case ClockIn ignored -> Status.WORKING;
                case BreakStart ignored -> Status.ON_BREAK;
                case BreakEnd ignored -> Status.WORKING;
                case ClockOut ignored -> Status.FINISHED;
            };
        }
        return status;
    }

    /**
     * 打刻列を畳み込む。
     *
     * @param requireFinished 退勤まで完了していることを要求するか
     */
    private List<TimeRange> fold(boolean requireFinished) {
        List<TimeRange> ranges = new ArrayList<>();
        Status status = Status.NOT_STARTED;
        LocalDateTime segmentStart = null;

        for (TimeClockEvent event : events) {
            switch (event) {
                case ClockIn in -> {
                    requireStatus(status, Status.NOT_STARTED, "出勤", in.occurredAt());
                    status = Status.WORKING;
                    // 労働の開始側は秒を切り捨てる。時刻が早くなり、労働時間は長くなる（BR-01）
                    segmentStart = TimeRange.floorToMinute(in.occurredAt());
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
                    segmentStart = TimeRange.floorToMinute(be.occurredAt());
                }
                case ClockOut out -> {
                    requireStatus(status, Status.WORKING, "退勤", out.occurredAt());
                    addRange(ranges, segmentStart, TimeRange.ceilToMinute(out.occurredAt()));
                    status = Status.FINISHED;
                    segmentStart = null;
                }
            }
            // default 句を書かない。打刻種別を追加した瞬間にここがコンパイルエラーになる
        }

        // ★ ここを忘れると、退勤していない日の最後の区間が黙って落ちる
        if (requireFinished && status != Status.FINISHED && !events.isEmpty()) {
            throw new InvalidTimeClockSequenceException(
                    "退勤打刻がないため労働時間を確定できません（状態: %s）".formatted(status));
        }
        return List.copyOf(ranges);
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
    private static void addRange(List<TimeRange> ranges, LocalDateTime start, LocalDateTime end) {
        if (start != null && start.isBefore(end)) {
            ranges.add(new TimeRange(start, end));
        }
    }
}
