package jp.co.sample.kintai.leave.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 年休の取得の申請（BR-16）。集約。
 *
 * <p><strong>1 申請 = 1 日。</strong> BR-16 は 1 日単位のみと定めており、
 * 連続した休暇をまとめると「3 日のうち 1 日だけ取り消す」「1 日だけ却下する」の扱いが要る。
 * 1 申請 1 日なら、その状態が型として存在しない。
 *
 * @param leaveDate  取得日
 * @param reason     申請の理由。<strong>任意。</strong> 時季指定は労働者の権利であり（39 条 5 項）、
 *                   必須にすると「理由が不十分だから却下する」という運用を招く
 * @param grantId    承認時に確定する配分先（先入先出・BR-15）
 * @param canceledBy 取り消した人。本人（取下げ）か、取得日の当日以降なら人事
 */
public record PaidLeaveRequest(PaidLeaveRequestId id, EmployeeId employeeId,
                               LocalDate leaveDate, Optional<String> reason,
                               LeaveRequestStatus status, LocalDateTime requestedAt,
                               Optional<PaidLeaveGrantId> grantId,
                               Optional<EmployeeId> decidedBy,
                               Optional<LocalDateTime> decidedAt,
                               Optional<String> comment,
                               Optional<EmployeeId> canceledBy,
                               Optional<LocalDateTime> canceledAt,
                               long version) {

    public PaidLeaveRequest {
        if (id == null || employeeId == null || leaveDate == null || reason == null
                || status == null || requestedAt == null || grantId == null
                || decidedBy == null || decidedAt == null || comment == null
                || canceledBy == null || canceledAt == null) {
            throw new IllegalArgumentException("年休の申請の項目に null は許されません");
        }
        // ★ 状態と付随項目の整合。DB の paid_leave_requests_state_check に対応する
        switch (status) {
            case SUBMITTED -> requireEmpty(grantId, decidedBy, decidedAt, canceledBy, canceledAt);
            case APPROVED -> {
                requirePresent(grantId, "配分先");
                requirePresent(decidedBy, "承認者");
                requirePresent(decidedAt, "承認日時");
                requireEmpty(canceledBy, canceledAt);
            }
            case REJECTED -> {
                requirePresent(decidedBy, "却下した人");
                requirePresent(decidedAt, "却下日時");
                if (comment.filter(text -> !text.isBlank()).isEmpty()) {
                    throw new IllegalArgumentException("却下には理由が必要です");
                }
                requireEmpty(grantId, canceledBy, canceledAt);
            }
            case CANCELED -> {
                requirePresent(canceledBy, "取り消した人");
                requirePresent(canceledAt, "取消日時");
                // ★ 配分を外す。残ったままだと残日数が戻らない
                requireEmpty(grantId);
                // ★ decidedBy / decidedAt は残す。
                //   消すと、承認済みだった申請を取り消したときに
                //   誰がいつ承認したのかが行から消える
            }
        }
    }

    /** 新しい申請を作る。<strong>本人しか作れない</strong>（4.2）。 */
    public static PaidLeaveRequest submit(PaidLeaveRequestId id, EmployeeId requester,
                                          EmployeeId employeeId, LocalDate leaveDate,
                                          Optional<String> reason, LocalDateTime at) {
        // ★ 代理申請を認めない。時季指定は本人の意思表示であり、人事でも代わりには出せない
        if (!requester.equals(employeeId)) {
            throw new NotTheRequesterException(employeeId);
        }
        return new PaidLeaveRequest(id, employeeId, leaveDate, reason,
                LeaveRequestStatus.SUBMITTED, at,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 1L);
    }

    /**
     * 承認する（BR-16）。
     *
     * <p><strong>自己承認の禁止を、承認者の判定より先に検査する。</strong>
     * {@code ApproverPolicy} は BR-11 の手順 4 で本人を承認者から外すので、
     * アプリケーション層を通すとこの検査は一度も働かない。
     * 禁止を消してもテストが 1 件も落ちない状態になる（落とし穴 58）。
     */
    public PaidLeaveRequest approve(EmployeeId approver, PaidLeaveGrantId allocatedTo,
                                    LocalDateTime at) {
        if (approver.equals(employeeId)) {
            throw new SelfDecisionException(employeeId);
        }
        requireSubmitted();
        return new PaidLeaveRequest(id, employeeId, leaveDate, reason,
                LeaveRequestStatus.APPROVED, requestedAt,
                Optional.of(allocatedTo), Optional.of(approver), Optional.of(at),
                comment, Optional.empty(), Optional.empty(), version);
    }

    /** 却下する。理由が必須。 */
    public PaidLeaveRequest reject(EmployeeId approver, String reasonForRejection,
                                   LocalDateTime at) {
        if (approver.equals(employeeId)) {
            throw new SelfDecisionException(employeeId);
        }
        requireSubmitted();
        return new PaidLeaveRequest(id, employeeId, leaveDate, reason,
                LeaveRequestStatus.REJECTED, requestedAt,
                Optional.empty(), Optional.of(approver), Optional.of(at),
                Optional.ofNullable(reasonForRejection),
                Optional.empty(), Optional.empty(), version);
    }

    /**
     * 本人が取り下げる（BR-16）。
     *
     * <p><strong>申請中はいつでも取り下げられる。</strong>
     * 「取得日の前日まで」は承認済みの取消に効く期限である。
     * 申請中にも当てると、承認者が決裁しないまま取得日と月末が過ぎ、
     * 人事が締めた申請が<strong>どの状態にも遷移できなくなる</strong>（落とし穴 93）。
     */
    public PaidLeaveRequest cancel(EmployeeId requester, LocalDate today, LocalDateTime at) {
        if (!requester.equals(employeeId)) {
            throw new NotTheRequesterException(employeeId);
        }
        if (!isCancelableBySelf(today)) {
            throw new NotCancelableException(leaveDate, status);
        }
        return canceled(requester, at);
    }

    /**
     * 人事が取り消す（BR-16）。
     *
     * <p>取得日の<strong>当日以降</strong>に限る。前日までは本人が取り下げる。
     * 訂正申請（BR-09）が動かせるのは打刻だけで、年休の状態は動かせないので、
     * この経路が無いと<strong>年休を 1 日消費したままその日も働く</strong>ことになる。
     */
    public PaidLeaveRequest revoke(EmployeeId actor, String reasonForRevocation,
                                   LocalDate today, LocalDateTime at) {
        if (status != LeaveRequestStatus.APPROVED) {
            throw new NotCancelableException(leaveDate, status);
        }
        if (today.isBefore(leaveDate)) {
            // 前日までは本人が取り下げられる。人事が先回りして取り消す理由が無い
            throw new NotCancelableException(leaveDate, status);
        }
        if (reasonForRevocation == null || reasonForRevocation.isBlank()) {
            throw new IllegalArgumentException("人事による取消には理由が必要です");
        }
        return new PaidLeaveRequest(id, employeeId, leaveDate, reason,
                LeaveRequestStatus.CANCELED, requestedAt,
                Optional.empty(), decidedBy, decidedAt,
                Optional.of(reasonForRevocation), Optional.of(actor), Optional.of(at), version);
    }

    /**
     * 本人が取り消せるか。基準は {@code Clock} から解決した「今日」。
     *
     * <p>{@code today.isBefore(leaveDate)} が「前日まで」である。
     * {@code !today.isAfter(leaveDate)} と書くと<strong>当日まで取り消せてしまう。</strong>
     */
    public boolean isCancelableBySelf(LocalDate today) {
        return switch (status) {
            // 承認されていない申請は年休を消費していないので、期限を設けない
            case SUBMITTED -> true;
            case APPROVED -> today.isBefore(leaveDate);
            case REJECTED, CANCELED -> false;
        };
    }

    /** 決着していない（＝年休を占有している）か。 */
    public boolean isActive() {
        return status == LeaveRequestStatus.SUBMITTED || status == LeaveRequestStatus.APPROVED;
    }

    /** 承認済みの配分。承認されていなければ空。 */
    public Optional<LeaveAllocation> allocation() {
        return status == LeaveRequestStatus.APPROVED
                ? grantId.map(id -> new LeaveAllocation(id, leaveDate))
                : Optional.empty();
    }

    private PaidLeaveRequest canceled(EmployeeId actor, LocalDateTime at) {
        return new PaidLeaveRequest(id, employeeId, leaveDate, reason,
                LeaveRequestStatus.CANCELED, requestedAt,
                Optional.empty(), decidedBy, decidedAt, comment,
                Optional.of(actor), Optional.of(at), version);
    }

    private void requireSubmitted() {
        if (status != LeaveRequestStatus.SUBMITTED) {
            throw new AlreadyDecidedException(status);
        }
    }

    @SafeVarargs
    private static void requireEmpty(Optional<?>... values) {
        for (Optional<?> value : values) {
            if (value.isPresent()) {
                throw new IllegalArgumentException(
                        "この状態では埋まっていてはならない項目に値があります: " + value.get());
            }
        }
    }

    private static void requirePresent(Optional<?> value, String name) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("この状態では %s が必要です".formatted(name));
        }
    }
}
