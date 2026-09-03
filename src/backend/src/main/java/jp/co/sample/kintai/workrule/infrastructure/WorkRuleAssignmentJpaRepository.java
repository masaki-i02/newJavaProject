package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 就業規則の適用 の Spring Data リポジトリ。
 *
 * <p><strong>トップレベルに置く。</strong>
 * Spring Data はクラスの内側に入れ子にしたインタフェースを走査しないので、
 * まとめて 1 ファイルに書くと Bean が作られない。
 */
interface WorkRuleAssignmentJpaRepository extends JpaRepository<WorkRuleAssignmentEntity, UUID> {

    List<WorkRuleAssignmentEntity> findByEmployeeIdOrderByValidFrom(UUID employeeId);

    /**
     * 指定日に規則が適用されていない在籍者。
     *
     * <p>「在籍者全員に規則が適用されている」ことは DB では守れないので、
     * ここで検知する。退職日は最終在籍日なので {@code >=} で比べる。
     */
    @Query(value = """
            SELECT e.id
              FROM employees e
             WHERE e.hired_on <= :date
               AND (e.retired_on IS NULL OR e.retired_on >= :date)
               AND NOT EXISTS (
                   SELECT 1 FROM work_rule_assignments a
                    WHERE a.employee_id = e.id
                      AND a.valid_from <= :date
                      AND (a.valid_to IS NULL OR a.valid_to > :date))
             ORDER BY e.employee_number
            """, nativeQuery = true)
    List<UUID> findEmployeesWithoutRuleOn(@Param("date") LocalDate date);
}
