package jp.co.sample.probe.domain;

import jakarta.persistence.Column;

/** AR-01 違反。ドメインが永続化の都合（カラム定義）を知っている。 */
public class UsesJakartaPersistence {

    @Column(name = "working_minutes")
    private long workingMinutes;

    public long workingMinutes() {
        return workingMinutes;
    }
}
