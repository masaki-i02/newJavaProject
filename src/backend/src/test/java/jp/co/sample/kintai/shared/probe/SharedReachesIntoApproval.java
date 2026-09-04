package jp.co.sample.kintai.shared.probe;

import jp.co.sample.kintai.approval.domain.AttendanceState;

/** AR-10 違反。{@code shared} が {@code approval} を知っている状態。 */
public class SharedReachesIntoApproval {

    public AttendanceState state() {
        return AttendanceState.DRAFT;
    }
}
