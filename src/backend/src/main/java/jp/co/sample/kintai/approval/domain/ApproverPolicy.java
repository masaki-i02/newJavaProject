package jp.co.sample.kintai.approval.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.Managership;
import jp.co.sample.kintai.employee.domain.OrganizationChart;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 誰が承認者かを決める（BR-11）。
 *
 * <p>{@link OrganizationChart} が返す<strong>組織構造の事実</strong>に対して、
 * <strong>承認の業務ルール</strong>を適用する。
 * 自己承認の禁止・退職者のスキップ・人事へのエスカレーションはここの責務であり、
 * 組織図の側には置かない。置くと、組織を引くたびに承認のルールが付いてくる
 * （アーキテクチャ設計書 6.4）。
 *
 * <p><strong>2 つの日付を取り違えない。</strong>
 * 所属・部署・部署長は<strong>基準日</strong>（BR-11 の 1）で引き、
 * 部署長が在籍しているかだけは<strong>承認を行う時点</strong>で見る。
 * 取り違えると、退職済みの部署長が承認者として返る。
 */
public final class ApproverPolicy {

    private final OrganizationChart chart;

    public ApproverPolicy(OrganizationChart chart) {
        if (chart == null) {
            throw new IllegalArgumentException("組織図に null は許されません");
        }
        this.chart = chart;
    }

    /**
     * 承認者を決める。
     *
     * @param today 承認を行う時点。<strong>在籍判定にだけ使う</strong>
     */
    public Approver resolve(EmployeeId target, YearMonth month, LocalDate today) {
        if (target == null || month == null || today == null) {
            throw new IllegalArgumentException("承認者の解決に null は許されません");
        }
        // BR-11 の 1。月中に所属が始まっていればその日、そうでなければ月初日。
        // ★ この例外が無いと、月中入社の社員は初月の承認者が導出できず、
        //   勤怠を永久に締められない
        LocalDate basis = chart.assignmentStartWithin(target, month).orElse(month.atDay(1));

        Optional<Department> own = chart.departmentOf(target, basis);
        if (own.isEmpty()) {
            // 対象月にまったく所属が無い（入社前・退職後）。
            // その月には月次勤怠が存在しないので、提出も承認も起きない
            return Approver.none(List.of());
        }

        List<ResolutionStep> path = new ArrayList<>();
        for (Department department : chart.selfAndAncestorsOf(own.get().id())) {
            SkipReason reason = skipReasonFor(department, target, basis, today);
            path.add(new ResolutionStep(department, reason));
            if (reason == SkipReason.NONE) {
                return Approver.of(chart.managerOf(department.id(), basis).orElseThrow(),
                        path);
            }
        }
        // BR-11 の 5。遡っても得られなければ人事が承認する
        return Approver.humanResources(path);
    }

    /**
     * その部署で承認者が決まらない理由。決まるなら {@link SkipReason#NONE}。
     *
     * <p>判定の順序に意味がある。廃止された部署には長がいないので、
     * 先に長の有無を見ると理由が {@code NO_MANAGER} になり、
     * <strong>「部署が廃止されている」という本当の理由が残らない。</strong>
     */
    private SkipReason skipReasonFor(Department department, EmployeeId target,
                                     LocalDate basis, LocalDate today) {
        if (!department.isActiveOn(basis)) {
            return SkipReason.DEPARTMENT_ABOLISHED;
        }
        Optional<Managership> manager = chart.managerOf(department.id(), basis);
        if (manager.isEmpty()) {
            return SkipReason.NO_MANAGER;
        }
        if (manager.get().employeeId().equals(target)) {
            // BR-11 の 4。自分は自分を承認できないので、さらに上へ遡る
            return SkipReason.SELF_APPROVAL_AVOIDED;
        }
        // ★ 在籍判定だけ「今日」を使う。基準日で見ると、
        //   その後に退職した部署長が承認者として返り、誰も承認できなくなる
        if (!chart.isActiveOn(manager.get().employeeId(), today)) {
            return SkipReason.MANAGER_RETIRED;
        }
        return SkipReason.NONE;
    }
}
