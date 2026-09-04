package jp.co.sample.kintai.leave.domain;

import java.util.UUID;

/** 年休の申請の識別子。 */
public record PaidLeaveRequestId(UUID value) {

    public PaidLeaveRequestId {
        if (value == null) {
            throw new IllegalArgumentException("申請の識別子に null は許されません");
        }
    }

    public static PaidLeaveRequestId generate() {
        return new PaidLeaveRequestId(UUID.randomUUID());
    }
}
