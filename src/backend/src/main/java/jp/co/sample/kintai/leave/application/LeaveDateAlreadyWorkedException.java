package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * すでに実労働のある日の年休を承認しようとした（BR-16）。
 *
 * <p>その日は所定総から除かれるのに実労働もあるので、
 * <strong>不足時間が過少に出る一方で社員は年休を 1 日失う</strong>（落とし穴 97）。
 */
public final class LeaveDateAlreadyWorkedException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    LeaveDateAlreadyWorkedException(LocalDate leaveDate) {
        super("その日には既に労働の記録があります: " + leaveDate);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:leave-date-already-worked";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "その日は既に労働しています";
    }
}
