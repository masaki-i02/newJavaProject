package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 承認者が見てよい範囲（要件定義書 4.1）。
 *
 * <p><strong>規則はこの 1 文である。</strong>
 * <em>承認者は、その日に自分が長を務めている部署と、その配下の部署に所属する社員を見られる。</em>
 *
 * <p>この規則には<strong>向きの違う 2 つの問い方</strong>があり、
 * どちらも実際に必要である。
 *
 * <table>
 *   <caption>2 つの向き</caption>
 *   <tr><th>問い</th><th>向き</th><th>計算量</th><th>使う場所</th></tr>
 *   <tr>
 *     <td>この 1 人を見てよいか</td>
 *     <td>対象の所属部署から根へ<strong>上向き</strong></td>
 *     <td>O(階層の深さ)＝高々 3 段</td>
 *     <td>{@link OrganizationBackedEmployeeVisibility}（一覧の行ごとに呼ばれる）</td>
 *   </tr>
 *   <tr>
 *     <td>見てよい部署はどれか</td>
 *     <td>長を務める部署から<strong>下向き</strong></td>
 *     <td>O(配下の部署数)</td>
 *     <td>組織図の絞り込み（SC-14）</td>
 *   </tr>
 * </table>
 *
 * <p><strong>1 つに畳まない。</strong> 上向きだけにすると、
 * 組織図が部署の数だけ再帰クエリを投げることになる。
 * 下向きだけにすると、<strong>社員一覧の 1 行ごとに配下すべてを展開する</strong>
 * （{@code EmployeeDirectoryService.list} は行ごとに {@code canView} を呼ぶ）。
 *
 * <p><strong>畳まないかわりに、同じクラスに置いて 1 つのテストで縛る。</strong>
 * {@code UT-EMP-21} が組織を 1 つ組み立て、
 * すべての (承認者, 社員) の組について 2 つの向きが同じ答えを返すことを確かめる。
 * 規則を変えるときは両方を直さないとそのテストが落ちる。
 * 別々のクラスに置いたままだと、片方だけ直しても誰も気づけない（落とし穴 137）。
 *
 * <p>本人・{@code HR}・{@code ADMIN} の扱いは<strong>ここには無い</strong>。
 * それは「承認者の範囲」ではなく閲覧の可否そのものなので、
 * {@link OrganizationBackedEmployeeVisibility} が持つ。
 */
public final class ApproverScope {

    private final OrganizationChart chart;
    private final ManagershipRepository managerships;
    private final DepartmentRepository departments;

    public ApproverScope(OrganizationChart chart, ManagershipRepository managerships,
                         DepartmentRepository departments) {
        if (chart == null || managerships == null || departments == null) {
            throw new IllegalArgumentException("承認者の範囲の組み立てに null は許されません");
        }
        this.chart = chart;
        this.managerships = managerships;
        this.departments = departments;
    }

    /**
     * 上向き。{@code manager} が {@code target} を配下に持つか。
     *
     * <p>所属が無い社員は<strong>誰の配下でもない</strong>。
     * 上長を決めようが無いので、承認者へ開かない。
     */
    public boolean covers(EmployeeId manager, EmployeeId target, LocalDate asOf) {
        Optional<Department> department = chart.departmentOf(target, asOf);
        if (department.isEmpty()) {
            return false;
        }
        return chart.selfAndAncestorsOf(department.get().id()).stream()
                .anyMatch(ancestor -> isManagedBy(ancestor.id(), manager, asOf));
    }

    /**
     * 下向き。{@code manager} が見てよい部署。
     *
     * <p><strong>ロールではなく、その日に長を務めている事実で決める。</strong>
     * {@code APPROVER} は認証時に {@code managerships} から導出される値なので、
     * ロールを先に見ても同じことを二度訊くだけになる（落とし穴 77）。
     * 長を務めていなければ空集合が返る。
     *
     * <p>空集合は<strong>「見てよい部署が無い」</strong>を意味する。
     * 「絞らない」（人事・管理者）と取り違えると全社が見えるので、
     * 呼ぶ側で区別すること。
     */
    public Set<DepartmentId> departmentsOf(EmployeeId manager, LocalDate asOf) {
        Set<DepartmentId> scope = new LinkedHashSet<>();
        for (Managership managership : managerships.findByManager(manager, asOf)) {
            departments.findSelfAndDescendants(managership.departmentId()).stream()
                    .map(Department::id)
                    .forEach(scope::add);
        }
        return scope;
    }

    private boolean isManagedBy(DepartmentId departmentId, EmployeeId manager,
                                LocalDate asOf) {
        return chart.managerOf(departmentId, asOf)
                .map(managership -> managership.employeeId().equals(manager))
                .orElse(false);
    }
}
