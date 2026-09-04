package jp.co.sample.kintai.leave.domain;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 自分の年休の申請を自分で承認・却下しようとした（BR-11）。
 *
 * <p><strong>{@code not-approver} にまとめない。</strong>
 * {@code ApproverPolicy} は本人を承認者から外すので、まとめると
 * 自己承認の禁止を消してもテストが 1 件も落ちない（落とし穴 58）。
 */
public final class SelfDecisionException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    SelfDecisionException(EmployeeId employeeId) {
        super("自分の年休の申請を自分で決裁することはできません: " + employeeId.value());
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:self-approval";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.FORBIDDEN;
    }

    @Override
    public String title() {
        return "自分の申請は決裁できません";
    }
}
