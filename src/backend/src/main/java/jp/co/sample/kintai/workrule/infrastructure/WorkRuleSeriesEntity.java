package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 就業規則の系列の永続化。 */
@Entity
@Table(name = "work_rule_series")
class WorkRuleSeriesEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "abolished_on")
    private LocalDate abolishedOn;

    @Version
    private long version;

    protected WorkRuleSeriesEntity() {
    }

    WorkRuleSeriesEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    LocalDate getAbolishedOn() {
        return abolishedOn;
    }

    void setAbolishedOn(LocalDate abolishedOn) {
        this.abolishedOn = abolishedOn;
    }
}
