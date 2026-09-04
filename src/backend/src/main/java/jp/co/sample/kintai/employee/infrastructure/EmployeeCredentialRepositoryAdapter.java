package jp.co.sample.kintai.employee.infrastructure;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import jp.co.sample.kintai.employee.domain.EmployeeCredential;
import jp.co.sample.kintai.employee.domain.EmployeeCredentialRepository;
import jp.co.sample.kintai.employee.domain.PasswordHash;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * {@link EmployeeCredentialRepository} の実装。
 *
 * <p>ドメインの record と JPA エンティティを変換する（ADR 0002）。
 * エンティティはこのパッケージの外に出さない。
 */
@Repository
class EmployeeCredentialRepositoryAdapter implements EmployeeCredentialRepository {

    private final EmployeeCredentialJpaRepository jpa;

    EmployeeCredentialRepositoryAdapter(EmployeeCredentialJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<EmployeeCredential> find(EmployeeId employeeId) {
        return jpa.findById(employeeId.value())
                .map(entity -> new EmployeeCredential(
                        new EmployeeId(entity.getEmployeeId()),
                        new PasswordHash(entity.getPasswordHash()),
                        entity.getPasswordChangedAt()
                                .atZoneSameInstant(BusinessZone.ID).toLocalDateTime()));
    }

    @Override
    public void save(EmployeeCredential credential) {
        var changedAt = credential.passwordChangedAt().atZone(BusinessZone.ID)
                .toOffsetDateTime();
        jpa.findById(credential.employeeId().value())
                .ifPresentOrElse(
                        entity -> entity.update(credential.passwordHash().value(), changedAt),
                        () -> jpa.save(new EmployeeCredentialEntity(
                                credential.employeeId().value(),
                                credential.passwordHash().value(), changedAt)));
    }
}
