package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * {@link EmployeeVisibility} を組織図から決める（要件定義書 4.1）。
 *
 * <p>判断は 3 段である。
 * <ol>
 *   <li>本人はいつでも見られる</li>
 *   <li>{@code HR} / {@code ADMIN} は全社員を見られる</li>
 *   <li>{@code APPROVER} は<strong>自分が長を務める部署の配下</strong>だけ</li>
 * </ol>
 *
 * <p>3 は「対象の所属部署から根へ向かって辿り、途中の部署の長が依頼者か」で判定する。
 * <strong>部署ツリーを下向きに展開しない。</strong>
 * 上向きに辿れば、対象 1 人あたり高々「本部 → 部 → 課」の 3 段で済む。
 */
public final class OrganizationBackedEmployeeVisibility implements EmployeeVisibility {

    private final OrganizationChart chart;

    public OrganizationBackedEmployeeVisibility(OrganizationChart chart) {
        if (chart == null) {
            throw new IllegalArgumentException("組織図に null は許されません");
        }
        this.chart = chart;
    }

    @Override
    public boolean canView(Requester requester, EmployeeId target, LocalDate asOf) {
        if (requester == null || target == null || asOf == null) {
            throw new IllegalArgumentException("閲覧範囲の判定に null は許されません");
        }
        if (requester.isSelf(target) || requester.canReachEveryone()) {
            return true;
        }
        if (!requester.has(Role.APPROVER)) {
            return false;
        }
        Optional<Department> department = chart.departmentOf(target, asOf);
        if (department.isEmpty()) {
            // 所属が無い社員は、本人と HR / ADMIN しか見られない。
            // 上長を決めようが無いので、承認者へ開かない
            return false;
        }
        return chart.selfAndAncestorsOf(department.get().id()).stream()
                .anyMatch(ancestor -> chart.managerOf(ancestor.id(), asOf)
                        .map(managership -> managership.employeeId().equals(
                                requester.employeeId()))
                        .orElse(false));
    }
}
