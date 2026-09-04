package jp.co.sample.kintai.approval.domain;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import java.util.stream.Collectors;

import jp.co.sample.kintai.attendance.domain.RecordedTimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventId;
import jp.co.sample.kintai.attendance.domain.TimeClockSequence;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 打刻の訂正申請（BR-09）。
 *
 * <p>状態遷移は 3 つだけで、いずれも {@code SUBMITTED} からしか起こらない。
 *
 * <table>
 *   <caption>遷移</caption>
 *   <tr><th>遷移</th><th>実行者</th></tr>
 *   <tr><td>{@code SUBMITTED} → {@code APPROVED}</td><td>承認者</td></tr>
 *   <tr><td>{@code SUBMITTED} → {@code REJECTED}</td><td>承認者（理由必須）</td></tr>
 *   <tr><td>{@code SUBMITTED} → {@code CANCELED}</td><td>本人</td></tr>
 * </table>
 *
 * <p><strong>取下げを用意する。</strong>
 * DB の {@code correction_requests_pending_uk} が「同一勤務日の未処理は 1 件まで」を守るので、
 * 取下げが無いと、誤って申請した本人は<strong>承認者が却下するまで
 * 正しい申請を出し直せない。</strong> 承認者が不在ならその勤務日の訂正が滞留する。
 *
 * @param workDate  訂正する勤務日。<strong>打刻した暦日ではない</strong>（BR-03）
 * @param decidedBy 決裁した人。取下げでは本人が入る
 */
public record CorrectionRequest(CorrectionRequestId id, EmployeeId employeeId,
                                LocalDate workDate, List<CorrectionItem> items,
                                String reason, CorrectionStatus status,
                                LocalDateTime requestedAt,
                                Optional<EmployeeId> decidedBy,
                                Optional<LocalDateTime> decidedAt,
                                Optional<String> decisionComment) {

    public CorrectionRequest {
        if (id == null || employeeId == null || workDate == null || items == null
                || status == null || requestedAt == null || decidedBy == null
                || decidedAt == null || decisionComment == null) {
            throw new IllegalArgumentException("訂正申請の項目に null は許されません");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("訂正理由は必須です");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("訂正内容が空です");
        }
        items = List.copyOf(items);

        if (status.isPending() != decidedBy.isEmpty()) {
            throw new IllegalArgumentException(
                    "決裁の記録は決着した申請にだけ付きます: " + status);
        }
        if (decidedBy.isPresent() != decidedAt.isPresent()) {
            throw new IllegalArgumentException("決裁者と決裁日時は同時に決まります");
        }
        if (decidedAt.isPresent() && decidedAt.get().isBefore(requestedAt)) {
            throw new IllegalArgumentException(
                    "決裁は申請より後である必要があります: 申請 %s / 決裁 %s"
                            .formatted(requestedAt, decidedAt.get()));
        }
        // 却下は理由が必須。DB の rejection_comment_check と同じ不変条件
        if (status == CorrectionStatus.REJECTED
                && decisionComment.map(String::isBlank).orElse(true)) {
            throw new IllegalArgumentException("却下には理由が必要です");
        }
        // 自分の訂正を自分で承認・却下できない。取下げだけは本人が行う
        if (status != CorrectionStatus.CANCELED
                && decidedBy.map(employeeId::equals).orElse(false)) {
            throw new IllegalArgumentException("自分の訂正は自分で決裁できません");
        }
        if (status == CorrectionStatus.CANCELED
                && !decidedBy.map(employeeId::equals).orElse(false)) {
            throw new IllegalArgumentException("取下げを行えるのは本人だけです");
        }
    }

    /** 新しい申請。 */
    public static CorrectionRequest submit(CorrectionRequestId id, EmployeeId employeeId,
                                           LocalDate workDate, List<CorrectionItem> items,
                                           String reason, LocalDateTime requestedAt) {
        return new CorrectionRequest(id, employeeId, workDate, items, reason,
                CorrectionStatus.SUBMITTED, requestedAt,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * 承認する。
     *
     * <p><strong>自己承認を禁じる。</strong> 比べる相手は申請者である。
     * 訂正申請では申請者と対象社員が必ず同じなので、月次勤怠とは違って迷う余地がない。
     */
    public CorrectionRequest approve(EmployeeId approvedBy, LocalDateTime at) {
        requirePending("承認");
        requireNotSelf(approvedBy, "承認");
        return decided(CorrectionStatus.APPROVED, approvedBy, at, Optional.empty());
    }

    /** 却下する。<strong>理由が必須。</strong> */
    public CorrectionRequest reject(EmployeeId rejectedBy, LocalDateTime at,
                                    String comment) {
        requirePending("却下");
        requireNotSelf(rejectedBy, "却下");
        return decided(CorrectionStatus.REJECTED, rejectedBy, at,
                Optional.ofNullable(comment));
    }

    /** 取り下げる（本人）。 */
    public CorrectionRequest cancel(EmployeeId canceledBy, LocalDateTime at) {
        requirePending("取下げ");
        if (!employeeId.equals(canceledBy)) {
            throw new NotTheRequesterException(workDate);
        }
        return decided(CorrectionStatus.CANCELED, canceledBy, at, Optional.empty());
    }

    /** 取り消す打刻。追加だけの申請では空になる。 */
    public List<CorrectionItem.Revoke> revocations() {
        return items.stream().filter(CorrectionItem.Revoke.class::isInstance)
                .map(CorrectionItem.Revoke.class::cast).toList();
    }

    /** 追加する打刻。 */
    public List<CorrectionItem.Add> additions() {
        return items.stream().filter(CorrectionItem.Add.class::isInstance)
                .map(CorrectionItem.Add.class::cast).toList();
    }

    /**
     * 訂正を適用したあとの打刻列を組み立てる（BR-09）。
     *
     * <p><strong>承認を待たずに、申請の時点でこれを検査する。</strong>
     * 承認者が承認したあとで「その訂正を適用すると壊れる」と分かるのでは遅い。
     * 申請者に直させる。
     *
     * <p>並べ替えと正準化は {@link TimeClockSequence} が行うので、ここでは順序を作らない。
     * 順序の決め方をここにも書くと、同時刻の扱いが 2 か所に散る。
     *
     * @param current その勤務日の現在の有効な打刻
     */
    public TimeClockSequence applyTo(List<RecordedTimeClockEvent> current) {
        var revoked = revocations().stream()
                .map(CorrectionItem.Revoke::targetId).collect(Collectors.toSet());
        var events = new java.util.ArrayList<TimeClockEvent>();
        current.stream().filter(recorded -> !revoked.contains(recorded.id()))
                .map(RecordedTimeClockEvent::event).forEach(events::add);
        additions().stream().map(CorrectionItem.Add::event).forEach(events::add);
        return TimeClockSequence.of(events);
    }

    /**
     * 取り消す対象が、その勤務日に実在するか。
     *
     * <p>実在しない打刻を指す申請は DB の {@code correction_items_target_fk} でも弾かれるが、
     * <strong>外部キー違反は利用者に説明できない。</strong>
     * 申請の時点で確かめて、どの打刻が見つからないのかを返す。
     */
    public List<TimeClockEventId> missingTargets(List<RecordedTimeClockEvent> current) {
        var present = current.stream().map(RecordedTimeClockEvent::id)
                .collect(Collectors.toSet());
        return revocations().stream().map(CorrectionItem.Revoke::targetId)
                .filter(target -> !present.contains(target)).toList();
    }

    private CorrectionRequest decided(CorrectionStatus next, EmployeeId by,
                                      LocalDateTime at, Optional<String> comment) {
        return new CorrectionRequest(id, employeeId, workDate, items, reason, next,
                requestedAt, Optional.of(by), Optional.of(at), comment);
    }

    private void requirePending(String operation) {
        if (!status.isPending()) {
            throw new AlreadyDecidedException(operation, status, workDate);
        }
    }

    private void requireNotSelf(EmployeeId actor, String operation) {
        if (employeeId.equals(actor)) {
            throw new SelfDecisionException(operation, workDate);
        }
    }

    /**
     * すでに決着している申請への操作。
     *
     * <p><strong>状態が変われば通るわけではない</strong>が、
     * 「他の誰かが先に決裁した」という競合なので {@code CONFLICT}（409）にする。
     */
    public static final class AlreadyDecidedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        AlreadyDecidedException(String operation, CorrectionStatus status,
                                LocalDate workDate) {
            super("%s は決着済みの申請には行えません: 勤務日 %s / 状態 %s"
                    .formatted(operation, workDate, status));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:correction-already-decided";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.CONFLICT;
        }

        @Override
        public String title() {
            return "その訂正申請はすでに決着しています";
        }
    }

    /** 自分の訂正を自分で承認・却下しようとした（BR-09）。 */
    public static final class SelfDecisionException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        SelfDecisionException(String operation, LocalDate workDate) {
            super("自分の訂正申請は自分で%sできません: 勤務日 %s"
                    .formatted(operation, workDate));
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:self-correction-decision";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.FORBIDDEN;
        }

        @Override
        public String title() {
            return "自分の訂正申請は自分で決裁できません";
        }
    }

    /** 他人の申請を取り下げようとした。取下げは本人の意思表示である。 */
    public static final class NotTheRequesterException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        NotTheRequesterException(LocalDate workDate) {
            super("訂正申請を取り下げられるのは申請者本人だけです: 勤務日 " + workDate);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:not-the-requester";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.FORBIDDEN;
        }

        @Override
        public String title() {
            return "取り下げられるのは申請者本人だけです";
        }
    }
}
