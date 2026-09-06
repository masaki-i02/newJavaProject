package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 残日数が足りない（BR-16）。
 *
 * <p><strong>判定はその取得日に有効な付与で行う。</strong>
 * 合計の残日数が足りていても、その日に有効な付与が無ければ取得できない（BR-15）。
 * 日付を {@code detail} に含めるのは、合計だけを見た利用者が理由を理解できないため。
 */
public final class InsufficientPaidLeaveException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    InsufficientPaidLeaveException(LocalDate leaveDate, int pendingCount) {
        super("%s に有効な残日数がありません（未処理の申請 %d 件を含む）"
                .formatted(leaveDate, pendingCount));
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:insufficient-paid-leave";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.RULE_VIOLATION;
    }

    @Override
    public String title() {
        return "年次有給休暇の残日数が足りません";
    }
}
