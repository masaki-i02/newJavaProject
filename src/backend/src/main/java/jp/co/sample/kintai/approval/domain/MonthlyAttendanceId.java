package jp.co.sample.kintai.approval.domain;

import java.util.UUID;

/** 月次勤怠の識別子。 */
public record MonthlyAttendanceId(UUID value) {

    public MonthlyAttendanceId {
        if (value == null) {
            throw new IllegalArgumentException("月次勤怠の識別子に null は許されません");
        }
    }
}
