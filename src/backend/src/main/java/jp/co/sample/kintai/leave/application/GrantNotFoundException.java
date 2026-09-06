package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/** その日の付与が存在しない。 */
public final class GrantNotFoundException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    GrantNotFoundException(EmployeeId employeeId, LocalDate grantedOn) {
        super("%s の付与が見つかりません: 社員 %s".formatted(grantedOn, employeeId.value()));
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
        return "付与が見つかりません";
    }
}
