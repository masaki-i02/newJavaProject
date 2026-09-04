package jp.co.sample.kintai.employee.domain;

import java.util.List;
import java.util.Optional;

/** 部署のポート。 */
public interface DepartmentRepository {

    Optional<Department> findById(DepartmentId id);

    List<Department> findAll();

    /**
     * 自分自身から根まで、その順で返す。
     *
     * <p><strong>1 クエリで返す。</strong> 親を 1 段ずつ辿ると階層の深さだけ往復する。
     * 承認者の導出（BR-11）は root まで遡りうるので、そこが N+1 になる。
     */
    List<Department> findSelfAndAncestors(DepartmentId departmentId);

    /** 自分自身と配下すべて。組織図の閲覧範囲の絞り込みに使う。 */
    List<Department> findSelfAndDescendants(DepartmentId departmentId);

    /** その部署コードが<strong>現存する部署</strong>で使われているか。廃止済みは数えない。 */
    boolean existsActiveCode(DepartmentCode code);

    /**
     * 階層の変更を直列化する。
     *
     * <p><strong>循環検出トリガは他トランザクションの未コミットの変更を見ない。</strong>
     * 「A の親を C に」「C の親を A に」が同時に走ると、
     * どちらのトリガも循環を見つけられないまま両方がコミットされ、循環が成立する。
     *
     * <p>親の変更は稀な操作なので、テーブルロックで十分である。
     * {@code application} 層が親を変える前に呼ぶ。
     */
    void lockForHierarchyChange();

    void save(Department department);
}
