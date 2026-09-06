package jp.co.sample.kintai.leave.application;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 対象の社員が存在しない。 */
public final class EmployeeNotFoundException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    EmployeeNotFoundException(EmployeeId employeeId) {
        super("社員が見つかりません: " + employeeId.value());
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:resource-not-found";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.NOT_FOUND;
    }

    @Override
    public String title() {
        return "社員が見つかりません";
    }
}
