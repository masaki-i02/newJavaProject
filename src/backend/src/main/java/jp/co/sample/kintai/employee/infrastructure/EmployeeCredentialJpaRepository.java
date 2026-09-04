package jp.co.sample.kintai.employee.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data のリポジトリ。
 *
 * <p><strong>クラスの内側に入れ子にしない。</strong>
 * Spring Data は入れ子のインタフェースを走査せず、Bean が作られないまま
 * コンテキストの起動ごと失敗する（CLAUDE.md 落とし穴 48）。
 */
interface EmployeeCredentialJpaRepository extends JpaRepository<EmployeeCredentialEntity, UUID> {
}
