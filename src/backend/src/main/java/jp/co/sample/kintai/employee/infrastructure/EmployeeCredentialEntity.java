package jp.co.sample.kintai.employee.infrastructure;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 認証情報の永続化。<strong>パッケージプライベートに保つ</strong>（AR-05）。
 *
 * <p>{@code password_changed_at} は<strong>業務上の日時</strong>なので
 * {@code Clock} 由来の値を書く（CLAUDE.md 2.3）。
 * 「いつ変えたか」は運用の判断材料であり、監査のための時刻ではない。
 */
@Entity
@Table(name = "employee_credentials")
class EmployeeCredentialEntity {

    @Id
    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_changed_at", nullable = false)
    private OffsetDateTime passwordChangedAt;

    protected EmployeeCredentialEntity() {
    }

    EmployeeCredentialEntity(UUID employeeId, String passwordHash,
                             OffsetDateTime passwordChangedAt) {
        this.employeeId = employeeId;
        this.passwordHash = passwordHash;
        this.passwordChangedAt = passwordChangedAt;
    }

    UUID getEmployeeId() {
        return employeeId;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    OffsetDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    void update(String newHash, OffsetDateTime changedAt) {
        this.passwordHash = newHash;
        this.passwordChangedAt = changedAt;
    }
}
