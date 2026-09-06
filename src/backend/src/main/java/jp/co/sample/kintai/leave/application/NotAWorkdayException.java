package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/** 所定労働日でない日を指定した（BR-16 / BR-07）。 */
public final class NotAWorkdayException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    NotAWorkdayException(LocalDate leaveDate) {
        super("所定労働日ではありません: " + leaveDate);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:not-a-workday";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.RULE_VIOLATION;
    }

    @Override
    public String title() {
        return "所定労働日ではありません";
    }
}
