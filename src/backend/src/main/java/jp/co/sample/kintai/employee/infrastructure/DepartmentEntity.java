package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 部署の永続化。パッケージプライベート（AR-05）。 */
@Entity
@Table(name = "departments")
class DepartmentEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    /** {@code null} はルート。 */
    @Column(name = "parent_id")
    private UUID parentId;

    /** {@code null} は現存。 */
    @Column(name = "abolished_on")
    private LocalDate abolishedOn;

    @Version
    private long version;

    protected DepartmentEntity() {
    }

    DepartmentEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    String getCode() {
        return code;
    }

    void setCode(String code) {
        this.code = code;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    UUID getParentId() {
        return parentId;
    }

    void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    LocalDate getAbolishedOn() {
        return abolishedOn;
    }

    void setAbolishedOn(LocalDate abolishedOn) {
        this.abolishedOn = abolishedOn;
    }
}
