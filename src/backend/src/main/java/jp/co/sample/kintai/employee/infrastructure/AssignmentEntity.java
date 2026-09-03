package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 所属の永続化。パッケージプライベート（AR-05）。 */
@Entity
@Table(name = "assignments")
class AssignmentEntity {

    @Id
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "department_id", nullable = false)
    private UUID departmentId;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** {@code null} は無期限。ドメインでは番兵で表す（{@link Periods}）。 */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Version
    private long version;

    protected AssignmentEntity() {
    }

    AssignmentEntity(UUID id) {
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

    UUID getDepartmentId() {
        return departmentId;
    }

    void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
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
