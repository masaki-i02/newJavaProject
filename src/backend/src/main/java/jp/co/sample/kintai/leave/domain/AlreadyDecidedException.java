package jp.co.sample.kintai.leave.domain;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/** 既に決裁済み・取下げ済みの申請を動かそうとした。 */
public final class AlreadyDecidedException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    AlreadyDecidedException(LeaveRequestStatus status) {
        super("この申請は既に処理済みです: " + status);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:invalid-transition";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "既に処理済みです";
    }
}
