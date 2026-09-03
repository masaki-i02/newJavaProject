package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 部署長の永続化。パッケージプライベート（AR-05）。 */
@Entity
@Table(name = "managerships")
class ManagershipEntity {

    @Id
    private UUID id;

    @Column(name = "department_id", nullable = false)
    private UUID departmentId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /** {@code null} は無期限。 */
    @Column(name = "valid_to")
    private LocalDate validTo;

    @Version
    private long version;

    protected ManagershipEntity() {
    }

    ManagershipEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    UUID getDepartmentId() {
        return departmentId;
    }

    void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    UUID getEmployeeId() {
        return employeeId;
    }

    void setEmployeeId(UUID employeeId) {
        this.employeeId = employeeId;
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
