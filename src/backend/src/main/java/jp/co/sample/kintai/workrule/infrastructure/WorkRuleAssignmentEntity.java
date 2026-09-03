package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 就業規則の適用の永続化。
 *
 * <p><strong>指すのは系列であって版ではない。</strong>
 * 版を直接指すと、改定した瞬間に指し先が「過去の版」になる（ADR 0003）。
 */
@Entity
@Table(name = "work_rule_assignments")
class WorkRuleAssignmentEntity {

    @Id
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "work_rule_series_id", nullable = false)
    private UUID workRuleSeriesId;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Version
    private long version;

    protected WorkRuleAssignmentEntity() {
    }

    WorkRuleAssignmentEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    UUID getEmployeeId() {
        return employeeId;
    }

    void setEmployeeId(UUID employeeId) {
        this.employeeId = employeeId;
    }

    UUID getWorkRuleSeriesId() {
        return workRuleSeriesId;
    }

    void setWorkRuleSeriesId(UUID workRuleSeriesId) {
        this.workRuleSeriesId = workRuleSeriesId;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    LocalDate getValidTo() {
        return validTo;
    }

    void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }
}
