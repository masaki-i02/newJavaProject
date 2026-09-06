package jp.co.sample.kintai.leave.application;

import java.io.Serial;

import jp.co.sample.kintai.leave.domain.PaidLeaveRequestId;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/** 年休の申請が存在しない。 */
public final class LeaveRequestNotFoundException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    LeaveRequestNotFoundException(PaidLeaveRequestId id) {
        super("年休の申請が見つかりません: " + id.value());
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
        return "申請が見つかりません";
    }
}
