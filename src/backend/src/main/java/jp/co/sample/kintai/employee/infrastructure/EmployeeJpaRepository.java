package jp.co.sample.kintai.employee.infrastructure;

import java.time.LocalDate;
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

    List<EmployeeEntity> findAllByOrderByEmployeeNumber();
}
