package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.YearMonth;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 対象月の月次勤怠が承認済み（BR-10 / BR-16）。
 *
 * <p>年休を動かすと月次清算だけが変わり、下書きへは戻らないので、
 * <strong>承認者が承認した内容と締めで確定する内容が黙って食い違う。</strong>
 */
public final class MonthNotEditableException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    MonthNotEditableException(YearMonth month) {
        super("対象月の月次勤怠が承認済みです: " + month);
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:month-not-editable";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "対象月は承認済みです";
    }
}
