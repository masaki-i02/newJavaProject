package jp.co.sample.kintai.payroll.domain;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/** その出力の記録が無い。 */
public final class PayrollExportNotFoundException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PayrollExportNotFoundException(PayrollExportId id) {
        super("給与連携の出力が見つかりません: " + id.value());
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:payroll-export-not-found";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.NOT_FOUND;
    }

    @Override
    public String title() {
        return "給与連携の出力が見つかりません";
    }
}
