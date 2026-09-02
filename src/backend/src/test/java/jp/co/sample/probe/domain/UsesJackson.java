package jp.co.sample.probe.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** AR-01 違反。ドメインが API の都合（JSON の項目名）を知っている。 */
public class UsesJackson {

    @JsonProperty("working_minutes")
    private long workingMinutes;

    public long workingMinutes() {
        return workingMinutes;
    }
}
