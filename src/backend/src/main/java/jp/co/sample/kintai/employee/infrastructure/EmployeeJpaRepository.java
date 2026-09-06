package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EmployeeJpaRepository extends JpaRepository<EmployeeEntity, UUID> {

    Optional<EmployeeEntity> findByEmployeeNumber(String employeeNumber);

    /**
     * 指定日に在籍していた社員。
     *
     * <p>退職日は<strong>最終在籍日</strong>なので {@code >=} で比べる。
     * {@code >} にすると退職日当日が漏れる（CLAUDE.md 落とし穴 10）。
     */
    @Query("""
            select e from EmployeeEntity e
             where e.hiredOn <= :asOf
               and (e.retiredOn is null or e.retiredOn >= :asOf)
             order by e.employeeNumber
            """)
    List<EmployeeEntity> findActiveOn(@Param("asOf") LocalDate asOf);

    /**
     * 指定日に退職していない社員。<strong>未来日入社の社員も含む。</strong>
     *
     * <p>{@link #findActiveOn} と違い、入社日で絞らない。名簿の用途である。
     */
    @Query("""
            select e from EmployeeEntity e
             where e.retiredOn is null or e.retiredOn >= :asOf
             order by e.employeeNumber
            """)
    List<EmployeeEntity> findNotRetiredOn(@Param("asOf") LocalDate asOf);

    /**
     * 期間と在籍期間が<strong>重なる</strong>社員。
     *
     * <p>{@link #findActiveOn} は基準日 1 点なので、月中入社か月中退職のどちらかが必ず落ちる。
     * 給与連携（BR-18）は<strong>その月に 1 日でも在籍した社員</strong>を数え上げるので、
     * 重なりで絞る。除くと退職者の最終給与が出ない。
     *
     * <p>{@code retiredOn} は<strong>最終在籍日</strong>（閉区間）なので {@code >=} で比べる。
     * {@code >} にすると退職日当日が漏れる（CLAUDE.md 落とし穴 10）。
     *
     * <p>社員番号は在籍者のあいだでしか一意でないので、
     * <strong>入社日まで含めて並べる</strong>（同じ番号の 2 人が並ぶ月がある）。
     */
    @Query("""
            select e from EmployeeEntity e
             where e.hiredOn < :toExclusive
               and (e.retiredOn is null or e.retiredOn >= :from)
             order by e.employeeNumber, e.hiredOn
            """)
    List<EmployeeEntity> findEmployedDuring(@Param("from") LocalDate from,
                                            @Param("toExclusive") LocalDate toExclusive);

    List<EmployeeEntity> findByIdInOrderByEmployeeNumber(Collection<UUID> ids);

    List<EmployeeEntity> findAllByOrderByEmployeeNumber();
}
