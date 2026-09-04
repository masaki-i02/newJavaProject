package jp.co.sample.kintai.leave.domain;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 取り消せない状態・時期だった（BR-16）。
 *
 * <p>承認済みを本人が取り消せるのは取得日の前日まで。
 * 当日以降は人事が理由を付けて取り消す。
 */
public final class NotCancelableException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    NotCancelableException(LocalDate leaveDate, LeaveRequestStatus status) {
        super("この年休は取り消せません: 取得日 %s / 状態 %s".formatted(leaveDate, status));
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:leave-not-cancelable";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "この年休は取り消せません";
    }
}
