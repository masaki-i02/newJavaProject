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

    void save(Department department);
}
