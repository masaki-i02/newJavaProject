package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AssignmentJpaRepository extends JpaRepository<AssignmentEntity, UUID> {

    /**
     * 指定日に有効な所属。
     *
     * <p>期間は<strong>半開区間</strong>なので、上限は {@code >} で比べる。
     * {@code >=} にすると異動日当日に 2 つの所属が返り、
     * DB の {@code assignments_no_overlap} が保証している一意性を
     * アプリケーション側で壊すことになる。
     */
    @Query("""
            select a from AssignmentEntity a
             where a.employeeId = :employeeId
               and a.validFrom <= :date
               and (a.validTo is null or a.validTo > :date)
            """)
    Optional<AssignmentEntity> findEffective(@Param("employeeId") UUID employeeId,
                                             @Param("date") LocalDate date);

    List<AssignmentEntity> findByEmployeeIdOrderByValidFrom(UUID employeeId);

    /** 現在開いている（上限が無い）所属。異動・退職で閉じる対象。 */
    @Query("""
            select a from AssignmentEntity a
             where a.employeeId = :employeeId and a.validTo is null
            """)
    List<AssignmentEntity> findOpen(@Param("employeeId") UUID employeeId);
}
