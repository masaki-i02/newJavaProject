package jp.co.sample.kintai.attendance.domain;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;

/**
 * 打刻の並びは正しいが、まだ退勤していないので労働時間を確定できない。
 *
 * <p>誤りではなく<strong>途中の状態</strong>である。退勤を打てば解消する。
 */
public final class IncompleteTimeClockSequenceException extends TimeClockSequenceException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IncompleteTimeClockSequenceException(String message) {
        super(message);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:incomplete-time-clock-sequence";
    }

    /**
     * <strong>入力の誤りではない。</strong>
     * 退勤を打てば解消するので、状態の衝突として扱う（409）。
     */
    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "退勤打刻がありません";
    }
}
