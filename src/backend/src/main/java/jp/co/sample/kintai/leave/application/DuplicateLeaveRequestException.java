package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 同じ日に有効な申請が既にある（BR-16）。
 *
 * <p>DB の {@code paid_leave_requests_active_uk} でも弾かれるが、
 * <strong>一意制約違反は利用者に説明できない</strong>（落とし穴 66）。
 * 申請の時点で確かめる。
 */
public final class DuplicateLeaveRequestException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    DuplicateLeaveRequestException(LocalDate leaveDate) {
        super("その日の年休の申請が既にあります: " + leaveDate);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:duplicate-leave-request";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "同じ日の申請が既にあります";
    }
}
