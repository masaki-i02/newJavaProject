package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/** 在籍していない日（入社前・退職後）を指定した（BR-16）。 */
public final class LeaveDateNotInServiceException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    LeaveDateNotInServiceException(LocalDate leaveDate) {
        super("その日は在籍していません: " + leaveDate);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:leave-date-not-in-service";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.RULE_VIOLATION;
    }

    @Override
    public String title() {
        return "在籍していない日です";
    }
}
