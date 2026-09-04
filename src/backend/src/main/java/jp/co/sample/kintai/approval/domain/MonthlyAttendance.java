package jp.co.sample.kintai.approval.domain;

import java.io.Serial;
import java.time.LocalDateTime;
import java.time.YearMonth;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 1 か月ぶんの勤怠の状態（BR-10）。
 *
 * <p>状態そのものは {@link MonthlyAttendanceStatus} が持ち、
 * この型は<strong>「誰の、いつの」</strong>を添えて遷移の可否を判断する。
 *
 * <p>遷移は {@code sealed interface} に対する網羅性検査つき {@code switch} で書く。
 * <strong>{@code default} 句を書かない</strong>ので、
 * 状態を足した瞬間にすべての遷移がコンパイルエラーになる。
 *
 * @param id         識別子
 * @param employeeId <strong>対象の社員。</strong>提出者とは限らない（代理提出）
 * @param month      対象月
 * @param status     現在の状態
 */
public record MonthlyAttendance(MonthlyAttendanceId id, EmployeeId employeeId,
                                YearMonth month, MonthlyAttendanceStatus status) {

    public MonthlyAttendance {
        if (id == null || employeeId == null || month == null || status == null) {
            throw new IllegalArgumentException("月次勤怠の項目に null は許されません");
        }
    }

    /** まだ何も起きていない月。<strong>行が無い月はこれと同じ扱いになる。</strong> */
    public static MonthlyAttendance draft(MonthlyAttendanceId id, EmployeeId employeeId,
                                          YearMonth month) {
        return new MonthlyAttendance(id, employeeId, month,
                new MonthlyAttendanceStatus.Draft());
    }

    /**
     * 提出する（本人、または退職者の最終月を人事が代理で）。
     *
     * <p>提出できるのは下書きだけである。
     * 二重提出は<strong>状態が変われば通る</strong>ので {@code CONFLICT} にする。
     */
    public MonthlyAttendance submit(EmployeeId submittedBy, LocalDateTime at) {
        requireStatus(MonthlyAttendanceStatus.Draft.class, "提出");
        return withStatus(new MonthlyAttendanceStatus.Submitted(submittedBy, at));
    }

    /**
     * 承認する（BR-11 の承認者）。
     *
     * <p><strong>自己承認を禁じる</strong>（BR-11 の 4）。
     * 禁じたいのは「本人が自分の勤怠を承認すること」なので、
     * 比べる相手は提出者ではなく<strong>対象の社員</strong>である。
     * 代理提出では提出者が人事になるため、提出者と比べると本人の承認を見逃す。
     */
    public MonthlyAttendance approve(EmployeeId approvedBy, LocalDateTime at) {
        var submitted = requireStatus(MonthlyAttendanceStatus.Submitted.class, "承認");
        if (approvedBy.equals(employeeId)) {
            throw new SelfApprovalException(employeeId, month);
        }
        return withStatus(new MonthlyAttendanceStatus.Approved(
                submitted.submittedBy(), submitted.submittedAt(), approvedBy, at));
    }

    /** 差し戻す（承認者の判断）。理由は呼び出し側が証跡へ残す。 */
    public MonthlyAttendance reject() {
        requireStatus(MonthlyAttendanceStatus.Submitted.class, "差戻し");
        return withStatus(new MonthlyAttendanceStatus.Draft());
    }

    /**
     * 訂正の承認により下書きへ戻す。
     *
     * <p><strong>差戻しとは別の遷移にする。</strong>
     * 遷移先は同じ下書きだが、差戻しは承認者の判断であるのに対し、
     * こちらは内容が変わったことによる巻き戻しで<strong>本人に非が無い。</strong>
     */
    public MonthlyAttendance revertByCorrection() {
        requireStatus(MonthlyAttendanceStatus.Submitted.class, "訂正による差戻し");
        return withStatus(new MonthlyAttendanceStatus.Draft());
    }

    /** 締める（人事）。<strong>締め済からの遷移は定義しない。</strong> */
    public MonthlyAttendance close(EmployeeId closedBy, LocalDateTime at) {
        var approved = requireStatus(MonthlyAttendanceStatus.Approved.class, "締め");
        return withStatus(new MonthlyAttendanceStatus.Closed(
                approved.submittedBy(), approved.submittedAt(),
                approved.approvedBy(), approved.approvedAt(), closedBy, at));
    }

    /**
     * 承認を取り消す（人事）。
     *
     * <p>承認後・締め前に誤りが見つかることは実務で起きる。
     * 戻す手段が無いと、締めてしまうか DB を直接触るしかなくなる。
     */
    public MonthlyAttendance revokeApproval() {
        requireStatus(MonthlyAttendanceStatus.Approved.class, "承認の取消");
        return withStatus(new MonthlyAttendanceStatus.Draft());
    }

    public boolean isClosed() {
        return status instanceof MonthlyAttendanceStatus.Closed;
    }

    private MonthlyAttendance withStatus(MonthlyAttendanceStatus next) {
        return new MonthlyAttendance(id, employeeId, month, next);
    }

    private <T extends MonthlyAttendanceStatus> T requireStatus(Class<T> expected,
                                                                String operation) {
        if (!expected.isInstance(status)) {
            throw new InvalidTransitionException(operation, status, month);
        }
        return expected.cast(status);
    }

    /**
     * その状態からは行えない遷移である。
     *
     * <p><strong>状態が変われば通るので {@code CONFLICT}（409）。</strong>
     * 権限の問題ではないので 403 にはしない。
     */
    public static final class InvalidTransitionException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        InvalidTransitionException(String operation, MonthlyAttendanceStatus status,
                                   YearMonth month) {
            super("%s は現在の状態からは行えません: 対象月 %s / 状態 %s"
                    .formatted(operation, month, status.state()));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:invalid-attendance-transition";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "その操作は現在の状態からは行えません";
        }
    }

    /**
     * 自分の勤怠を自分で承認しようとした（BR-11 の 4）。
     *
     * <p><strong>権限の不足なので 403。</strong>
     * 状態が変わっても通らない。別の承認者に承認してもらうしかない。
     */
    public static final class SelfApprovalException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        SelfApprovalException(EmployeeId employeeId, YearMonth month) {
            super("自分の勤怠は承認できません: 社員 %s / 対象月 %s"
                    .formatted(employeeId.value(), month));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:self-approval";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.FORBIDDEN;
        }

        @Override
        public String title() {
            return "自分の勤怠は承認できません";
        }
    }
}
