package jp.co.sample.kintai.shared.probe;

import jp.co.sample.kintai.attendance.domain.ClockSource;

/** AR-10 違反。{@code shared} が {@code attendance} を知っている状態。 */
public class SharedReachesIntoAttendance {

    public ClockSource source() {
        return ClockSource.WEB;
    }
}
