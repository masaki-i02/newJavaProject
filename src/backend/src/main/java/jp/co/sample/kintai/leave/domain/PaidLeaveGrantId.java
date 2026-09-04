package jp.co.sample.kintai.leave.domain;

import java.util.UUID;

/** 付与の識別子。 */
public record PaidLeaveGrantId(UUID value) {

    public PaidLeaveGrantId {
        if (value == null) {
            throw new IllegalArgumentException("付与の識別子に null は許されません");
        }
    }

    public static PaidLeaveGrantId generate() {
        return new PaidLeaveGrantId(UUID.randomUUID());
    }
}
