package jp.co.sample.probe.domain;

import java.time.LocalDate;

/** AR-09 違反。Clock を渡さずに現在時刻を取る。 */
public class CallsNowWithoutClock {

    public LocalDate today() {
        return LocalDate.now();
    }
}
