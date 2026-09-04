package jp.co.sample.kintai.approval.domain;

import java.util.UUID;

/** 訂正申請の識別子。 */
public record CorrectionRequestId(UUID value) {

    public CorrectionRequestId {
        if (value == null) {
            throw new IllegalArgumentException("訂正申請の識別子に null は許されません");
        }
    }
}
