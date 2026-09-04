package jp.co.sample.kintai.approval.domain;

import java.time.LocalDateTime;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 状態遷移の監査証跡（要件定義書 7 章）。
 *
 * <p><strong>遷移の前後だけでなく、種類も残す。</strong>
 * 差戻し（{@code REJECT}）と訂正による巻き戻し（{@code REVERT_BY_CORRECTION}）は
 * どちらも提出済 → 下書きだが、意味がまったく違う。
 * 前後の状態だけでは区別できない。
 *
 * @param comment 理由。差戻し・承認の取消・訂正による巻き戻し・代理提出では<strong>必須</strong>
 */
public record ApprovalEvent(MonthlyAttendanceId monthlyAttendanceId,
                            AttendanceState from, AttendanceState to,
                            ApprovalEventKind kind, EmployeeId actor,
                            Optional<String> comment, LocalDateTime occurredAt) {

    public ApprovalEvent {
        if (monthlyAttendanceId == null || from == null || to == null || kind == null
                || actor == null || comment == null || occurredAt == null) {
            throw new IllegalArgumentException("承認イベントの項目に null は許されません");
        }
        // ★ 下書きへ戻す遷移と代理提出は理由が必須（BR-10）。空白だけも許さない。
        //   同じ条件を DB の CHECK でも守る
        if (requiresComment(kind) && comment.map(String::isBlank).orElse(true)) {
            throw new IllegalArgumentException("この遷移には理由が必要です: " + kind);
        }
    }

    private static boolean requiresComment(ApprovalEventKind kind) {
        return switch (kind) {
            case REJECT, REVOKE_APPROVAL, REVERT_BY_CORRECTION, PROXY_SUBMIT -> true;
            case SUBMIT, APPROVE, CLOSE -> false;
        };
    }
}
