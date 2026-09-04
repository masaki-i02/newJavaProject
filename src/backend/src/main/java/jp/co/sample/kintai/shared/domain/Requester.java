package jp.co.sample.kintai.shared.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * その操作を誰の依頼として受け付けるか。
 *
 * <p><strong>「誰が」を引数で受け取る</strong>ための型である（CLAUDE.md 落とし穴 42）。
 * 認証済みの利用者をスレッドローカル（{@code SecurityContextHolder}）から
 * {@code application} 層が読みにいく形にすると、次の 2 つが起きる。
 *
 * <ul>
 *   <li>ユースケースが「誰の依頼か」を引数に持たなくなり、テストで別人を差し替えられない</li>
 *   <li>{@code application} が認証の枠組みに依存する。バッチや再計算のような
 *       利用者のいない経路から呼べなくなる</li>
 * </ul>
 *
 * <p><strong>閲覧範囲の判断はここではしない。</strong>
 * 「配下部署の社員か」は組織の状態に依存する業務判断であり、
 * {@code OrganizationChart} を引ける {@code application} 層が行う。
 * この型が答えるのは<strong>ロールを持っているかどうかだけ</strong>である。
 *
 * @param employeeId 依頼者
 * @param roles      認証時点で有効なロール。{@code APPROVER} は部署長の事実から導出される
 */
public record Requester(EmployeeId employeeId, Set<Role> roles) {

    public Requester {
        if (employeeId == null || roles == null) {
            throw new IllegalArgumentException("依頼者の項目に null は許されません");
        }
        if (!roles.contains(Role.EMPLOYEE)) {
            // 要件定義書 4 章。自分の打刻ができない社員は存在しない
            throw new IllegalArgumentException("全員が EMPLOYEE を持ちます: " + roles);
        }
        roles = roles.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** 自分自身についての操作か。 */
    public boolean isSelf(EmployeeId target) {
        return employeeId.equals(target);
    }

    /**
     * 全社員を対象にできるか。
     *
     * <p>人事とシステム管理者。<strong>承認者は含めない。</strong>
     * 承認者の範囲は「配下部署」であり、組織を引かないと決まらない。
     */
    public boolean canReachEveryone() {
        return has(Role.HR) || has(Role.ADMIN);
    }
}
