package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ManagershipJpaRepository extends JpaRepository<ManagershipEntity, UUID> {

    @Query("""
            select m from ManagershipEntity m
             where m.departmentId = :departmentId
               and m.validFrom <= :date
               and (m.validTo is null or m.validTo > :date)
            """)
    Optional<ManagershipEntity> findEffective(@Param("departmentId") UUID departmentId,
                                              @Param("date") LocalDate date);

    /** その社員が指定日に長を務めている部署。<strong>兼任があるので複数返りうる。</strong> */
    @Query("""
            select m from ManagershipEntity m
             where m.employeeId = :employeeId
               and m.validFrom <= :date
               and (m.validTo is null or m.validTo > :date)
            """)
    List<ManagershipEntity> findByManager(@Param("employeeId") UUID employeeId,
                                          @Param("date") LocalDate date);

    @Query("""
            select m from ManagershipEntity m
             where m.departmentId = :departmentId and m.validTo is null
            """)
    List<ManagershipEntity> findOpen(@Param("departmentId") UUID departmentId);

    /** その社員が務めていて、まだ開いている部署長の期間。兼任があるので複数になりうる。 */
    @Query("""
            select m from ManagershipEntity m
             where m.employeeId = :employeeId and m.validTo is null
            """)
    List<ManagershipEntity> findOpenByManager(@Param("employeeId") UUID employeeId);

    /** 指定日で閉じられている部署長の期間。退職の取消で開き直すために引く。 */
    @Query("""
            select m from ManagershipEntity m
             where m.employeeId = :employeeId and m.validTo = :toExclusive
            """)
    List<ManagershipEntity> findClosedAt(@Param("employeeId") UUID employeeId,
                                         @Param("toExclusive") LocalDate toExclusive);
}
