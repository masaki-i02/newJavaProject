package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import jp.co.sample.kintai.employee.domain.Role;

/**
 * 社員の永続化。<strong>パッケージプライベートに保つ</strong>（CLAUDE.md 4.3・AR-05）。
 *
 * <p>ドメインの {@code Employee}（不変な record）とは別の型にする。
 * JPA エンティティは可変で、識別子の等価性を持ち、遅延読み込みの制約を負う。
 * ドメインをこの制約に引きずられないようにする（ADR 0002）。
 *
 * <p>{@code created_at} / {@code updated_at} は<strong>マッピングしない。</strong>
 * DB の {@code now()} とトリガが管理する。監査のための時刻をアプリケーションから
 * 書けるようにすると偽装できる（CLAUDE.md 2.3「時刻の生成元」）。
 */
@Entity
@Table(name = "employees")
class EmployeeEntity {

    @Id
    private UUID id;

    @Column(name = "employee_number", nullable = false)
    private String employeeNumber;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(name = "hired_on", nullable = false)
    private LocalDate hiredOn;

    /** {@code null} は在籍中。ドメインでは {@code Optional.empty()}。 */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "employee_roles", joinColumns = @JoinColumn(name = "employee_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new LinkedHashSet<>();

    protected EmployeeEntity() {
    }

    EmployeeEntity(UUID id) {
        this.id = id;
    }

    UUID getId() {
        return id;
    }

    String getEmployeeNumber() {
        return employeeNumber;
    }

    void setEmployeeNumber(String employeeNumber) {
        this.employeeNumber = employeeNumber;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    String getEmail() {
        return email;
    }

    void setEmail(String email) {
        this.email = email;
    }

    LocalDate getHiredOn() {
        return hiredOn;
    }

    void setHiredOn(LocalDate hiredOn) {
        this.hiredOn = hiredOn;
    }

    LocalDate getRetiredOn() {
        return retiredOn;
    }

    void setRetiredOn(LocalDate retiredOn) {
        this.retiredOn = retiredOn;
    }

    Set<Role> getRoles() {
        return roles;
    }

    void setRoles(Set<Role> roles) {
        this.roles = new LinkedHashSet<>(roles);
    }
}
