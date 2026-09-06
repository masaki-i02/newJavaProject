package jp.co.sample.kintai.payroll.domain;

import java.util.UUID;

/** 給与連携の出力の識別子。 */
public record PayrollExportId(UUID value) {

    public PayrollExportId {
        if (value == null) {
            throw new IllegalArgumentException("出力の識別子に null は許されません");
        }
    }

    public static PayrollExportId generate() {
        return new PayrollExportId(UUID.randomUUID());
    }
}
