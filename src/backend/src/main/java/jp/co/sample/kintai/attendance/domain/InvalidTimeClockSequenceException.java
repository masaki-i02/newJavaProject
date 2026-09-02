package jp.co.sample.kintai.attendance.domain;

import java.io.Serial;

/**
 * 打刻の順序が状態機械に反する（勤務中でないのに休憩開始、二重の出勤など）。
 *
 * <p>並びそのものが誤っているので、あとから打刻を足しても直らない。
 * 訂正申請が要る。<strong>{@link IncompleteTimeClockSequenceException} とは区別する</strong>
 * ——退勤し忘れは並びが正しいまま途中なだけで、退勤を打てば解消するからである。
 * 同じ例外にすると、画面が「訂正申請へ」と「退勤を打ってください」を出し分けられない。
 */
public class InvalidTimeClockSequenceException extends TimeClockSequenceException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidTimeClockSequenceException(String message) {
        super(message);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:invalid-time-clock-sequence";
    }
}
