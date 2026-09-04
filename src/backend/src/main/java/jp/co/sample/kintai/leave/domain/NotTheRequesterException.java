package jp.co.sample.kintai.leave.domain;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 本人以外が申請・取下げをしようとした（BR-16）。
 *
 * <p><strong>代理申請を認めない。</strong> 時季指定は本人の意思表示であり、
 * 人事でも代わりには出せない。訂正申請と同じ判断である。
 */
public final class NotTheRequesterException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    NotTheRequesterException(EmployeeId employeeId) {
        super("年休の申請・取下げは本人しか行えません: " + employeeId.value());
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:not-the-requester";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.FORBIDDEN;
    }

    @Override
    public String title() {
        return "本人以外は操作できません";
    }
}
