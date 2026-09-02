package jp.co.sample.kintai.attendance.domain;

/** 打刻の順序が状態機械に反する。 */
public class InvalidTimeClockSequenceException extends RuntimeException {

    public InvalidTimeClockSequenceException(String message) {
        super(message);
    }
}
