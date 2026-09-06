package jp.co.sample.kintai.leave.application;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 取得日に有効な付与がまだ実体化していない（BR-15）。
 *
 * <p>申請の判定では到来予定の付与を仮に組み入れる（時季指定は労働者の権利）が、
 * <strong>配分は承認という行為の時点で下された決定</strong>なので、
 * まだ存在しない付与へ配分してはならない（ADR 0006）。
 */
public final class GrantNotYetIssuedException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    GrantNotYetIssuedException(LocalDate leaveDate) {
        super("%s に有効な付与がまだ行われていません".formatted(leaveDate));
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:grant-not-yet-issued";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "付与がまだ行われていません";
    }
}
