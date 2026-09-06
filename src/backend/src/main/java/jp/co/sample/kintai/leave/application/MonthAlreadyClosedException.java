package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.YearMonth;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 対象月が締め済み（BR-10 / BR-16）。
 *
 * <p><strong>{@link MonthNotEditableException} と分ける。</strong>
 * 承認済みは承認を取り消せば直せるので、利用者への案内がまったく違う。
 */
public final class MonthAlreadyClosedException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    MonthAlreadyClosedException(YearMonth month) {
        super("対象月は締め済みです: " + month);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:month-already-closed";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "対象月は締め済みです";
    }
}
