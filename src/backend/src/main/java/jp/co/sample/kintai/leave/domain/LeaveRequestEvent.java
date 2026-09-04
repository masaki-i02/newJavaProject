package jp.co.sample.kintai.leave.domain;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 年休の申請の状態遷移の証跡（要件 7 章・5 年保持）。
 *
 * <p><strong>取消も残す。</strong> 承認済みの取消は残日数を戻すので、
 * 「なぜこの日の残数が増えたか」を後から説明できる必要がある。
 *
 * @param fromStatus 遷移元。新規申請は空（行が無かった）
 */
public record LeaveRequestEvent(UUID id, PaidLeaveRequestId requestId,
                                Optional<LeaveRequestStatus> fromStatus,
                                LeaveRequestStatus toStatus, LeaveRequestEventKind kind,
                                EmployeeId actorId, Optional<String> comment,
                                LocalDateTime occurredAt) {

    public LeaveRequestEvent {
        if (id == null || requestId == null || fromStatus == null || toStatus == null
                || kind == null || actorId == null || comment == null || occurredAt == null) {
            throw new IllegalArgumentException("証跡の項目に null は許されません");
        }
        // ★ 却下と人事の取消は理由が必須。DB の events_reason_check に対応する
        if ((kind == LeaveRequestEventKind.REJECT || kind == LeaveRequestEventKind.REVOKE)
                && comment.filter(text -> !text.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("%s には理由が必要です".formatted(kind));
        }
    }

    public static LeaveRequestEvent of(PaidLeaveRequestId requestId,
                                       Optional<LeaveRequestStatus> from,
                                       LeaveRequestStatus to, LeaveRequestEventKind kind,
                                       EmployeeId actor, Optional<String> comment,
                                       LocalDateTime at) {
        return new LeaveRequestEvent(UUID.randomUUID(), requestId, from, to, kind, actor,
                comment, at);
    }
}
