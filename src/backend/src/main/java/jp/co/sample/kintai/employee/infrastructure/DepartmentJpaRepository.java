package jp.co.sample.kintai.employee.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DepartmentJpaRepository extends JpaRepository<DepartmentEntity, UUID> {

    /**
     * 自分自身から根まで、その順で返す。
     *
     * <p><strong>再帰 CTE で 1 クエリにする。</strong>
     * 親を 1 段ずつ辿ると階層の深さだけ往復する。承認者の導出（BR-11）は
     * 根まで遡りうるので、そこが N+1 になる。
     *
     * <p>JPQL に再帰 CTE は書けないのでネイティブクエリを使う（未決事項 #2 の判断）。
     * {@code depth} で並べることで「自分自身 → 根」の順が保証される。
     */
    @Query(value = """
            WITH RECURSIVE ancestors AS (
                SELECT d.*, 0 AS depth
                  FROM departments d
                 WHERE d.id = :id
                UNION ALL
                SELECT p.*, a.depth + 1
                  FROM departments p
                  JOIN ancestors a ON p.id = a.parent_id
            )
            SELECT id, code, name, parent_id, abolished_on, version,
                   created_at, updated_at
              FROM ancestors
             ORDER BY depth
            """, nativeQuery = true)
    List<DepartmentEntity> findSelfAndAncestors(@Param("id") UUID id);

    /** 自分自身と配下すべて。組織図の閲覧範囲の絞り込みに使う。 */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT d.*, 0 AS depth
                  FROM departments d
                 WHERE d.id = :id
                UNION ALL
                SELECT c.*, s.depth + 1
                  FROM departments c
                  JOIN descendants s ON c.parent_id = s.id
            )
            SELECT id, code, name, parent_id, abolished_on, version,
                   created_at, updated_at
              FROM descendants
             ORDER BY depth, code
            """, nativeQuery = true)
    List<DepartmentEntity> findSelfAndDescendants(@Param("id") UUID id);

    List<DepartmentEntity> findAllByOrderByCode();
}
