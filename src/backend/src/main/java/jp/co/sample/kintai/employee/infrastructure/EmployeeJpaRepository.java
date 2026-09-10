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

    /**
     * その社員番号の行を<strong>すべて</strong>返す。
     *
     * <p><strong>1 件に絞らない。</strong> `employees_employee_number_uk` は
     * `WHERE retired_on IS NULL` の部分一意インデックスなので、
     * 退職者の社員番号は再割り当てできる（落とし穴 121）。
     * {@code Optional} で受けると、番号を再利用した瞬間に
     * {@code IncorrectResultSizeDataAccessException} になり、
     * <strong>その番号では誰もログインできなくなる</strong>（理由の載らない 500）。
     *
     * <p>「その日に在籍しているか」の判定はドメインに任せる（落とし穴 69）。
     */
    List<EmployeeEntity> findAllByEmployeeNumber(String employeeNumber);

    /**
     * 指定日に退職していない社員。<strong>未来日入社の社員も含む。</strong>
     *
     * <p>{@link #findEmployedDuring} と違い、<strong>入社日で絞らない。</strong>
     * 名簿の用途である。絞ると未来日入社の社員が一覧に現れず、
     * 管理者が登録の成否を確かめられない（落とし穴 75）。
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
     * <p>給与連携（BR-18）は<strong>その月に 1 日でも在籍した社員</strong>を数え上げるので、
     * 重なりで絞る。除くと退職者の最終給与が出ない。
     *
     * <p><strong>「その 1 日に在籍しているか」もこの問いで答える。</strong>
     * 期間へ {@code [その日, 翌日)} を渡せば
     * {@code hiredOn <= その日 かつ (未退職 または 退職日 >= その日)} と等しい。
     * かつて {@code findActiveOn} という 1 日専用の問い合わせを別に持っていたが、
     * <strong>同じ述語が 2 か所にある</strong>ぶん、退職日の扱い（最終在籍日）を
     * 片方だけ直せる状態だった。しかも誰も呼んでいなかった（落とし穴 87）。
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
