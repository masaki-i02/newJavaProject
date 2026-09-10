package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;

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
 * <p>3 の「配下か」を決めるのは {@link ApproverScope} である。
 * <strong>同じ規則を組織図の絞り込み（SC-14）も使う</strong>ので、
 * 規則そのものは 1 か所に置き、ここは<strong>閲覧の可否</strong>だけを組み立てる。
 *
 * <p>ここが使うのは {@link ApproverScope#covers} の<strong>上向き</strong>である。
 * 一覧は行ごとにこの判定を呼ぶので、下向きに展開すると
 * 1 行ごとに配下すべてを組み立てることになる。
 */
public final class OrganizationBackedEmployeeVisibility implements EmployeeVisibility {

    private final ApproverScope scope;

    public OrganizationBackedEmployeeVisibility(ApproverScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("承認者の範囲に null は許されません");
        }
        this.scope = scope;
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
        // 所属が無い社員は、本人と HR / ADMIN しか見られない（ApproverScope が偽を返す）
        return scope.covers(requester.employeeId(), target, asOf);
    }
}
