package jp.co.sample.kintai.employee.domain;

import java.time.LocalDateTime;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 社員の認証情報。
 *
 * <p><strong>社員（{@link Employee}）と分けて持つ。</strong>
 * 氏名や所属を読むだけの経路でパスワードハッシュを一緒に運ぶと、
 * 応答へ載せる事故の面積が広がる。
 *
 * @param employeeId        社員
 * @param passwordHash      ハッシュ化されたパスワード
 * @param passwordChangedAt 最後に変更した日時。<strong>業務上の日時なので {@code Clock} 由来</strong>
 */
public record EmployeeCredential(EmployeeId employeeId, PasswordHash passwordHash,
                                 LocalDateTime passwordChangedAt) {

    public EmployeeCredential {
        if (employeeId == null || passwordHash == null || passwordChangedAt == null) {
            throw new IllegalArgumentException("認証情報の項目に null は許されません");
        }
    }
}
