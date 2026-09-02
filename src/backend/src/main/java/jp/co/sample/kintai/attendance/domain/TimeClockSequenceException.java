package jp.co.sample.kintai.attendance.domain;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainException;

/** 打刻の並びから労働時間を確定できない。 */
public abstract class TimeClockSequenceException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected TimeClockSequenceException(String message) {
        super(message);
    }
}
