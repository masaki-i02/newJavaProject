package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 社員。
 *
 * <p><strong>雇用形態を持たない。</strong>
 * 要件定義書 3.1 が「全社員の所定労働時間は 1 日 8 時間」と定めており、短時間勤務は対象外である。
 * 列を設けると BR-04 / BR-05 の前提と実態が食い違う。
 *
 * <p><strong>退職しても行を消さない。</strong>
 * 過去の勤怠・承認履歴が社員を参照する。
 *
 * @param id        識別子
 * @param number    社員番号。認証 ID を兼ねる
 * @param name      氏名
 * @param email     メールアドレス
 * @param hiredOn   入社日
 * @param retiredOn 退職日（<strong>最終在籍日</strong>）。空なら在籍中
 * @param roles     権限。{@link Role#EMPLOYEE} を必ず含む
 */
public record Employee(EmployeeId id, EmployeeNumber number, String name, Email email,
                       LocalDate hiredOn, Optional<LocalDate> retiredOn, Set<Role> roles) {

    public Employee {
        if (id == null || number == null || name == null || email == null
                || hiredOn == null || retiredOn == null || roles == null) {
            throw new IllegalArgumentException("社員の項目に null は許されません");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("氏名は必須です");
        }
        Optional<LocalDate> invalid = retiredOn.filter(retired -> retired.isBefore(hiredOn));
        if (invalid.isPresent()) {
            throw new BusinessRuleViolationException("要件 2.3",
                    "退職日が入社日より前になっています: 入社 %s / 退職 %s"
                            .formatted(hiredOn, invalid.get()));
        }
        // ★ 全社員が EMPLOYEE を持つ。自分の打刻ができない社員を生成できなくする（要件 4 章）
        if (!roles.contains(Role.EMPLOYEE)) {
            throw new BusinessRuleViolationException("要件 4",
                    "全社員が EMPLOYEE ロールを持つ必要があります: " + roles);
        }
        roles = Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    /** 在籍中の社員を作る。 */
    public static Employee active(EmployeeId id, EmployeeNumber number, String name,
                                  Email email, LocalDate hiredOn, Set<Role> roles) {
        return new Employee(id, number, name, email, hiredOn, Optional.empty(), roles);
    }

    /**
     * 在籍期間。
     *
     * <p><strong>退職日は最終在籍日なので、半開区間の上限は翌日になる。</strong>
     * 閉区間の感覚のまま扱うと退職日当日の 1 日が消え、
     * 最終日の勤怠の承認者が導出できなくなる（CLAUDE.md 落とし穴 10）。
     */
    public DateRange activePeriod() {
        return DateRange.closed(hiredOn, retiredOn);
    }

    public boolean isActiveOn(LocalDate date) {
        return activePeriod().contains(date);
    }

    public boolean isRetired() {
        return retiredOn.isPresent();
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** 退職させる。所属と部署長を閉じるのは application 層の責務（設計書 5 章）。 */
    public Employee retire(LocalDate lastDay) {
        return new Employee(id, number, name, email, hiredOn, Optional.of(lastDay), roles);
    }
}
