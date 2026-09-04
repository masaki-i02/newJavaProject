package jp.co.sample.kintai.approval.presentation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jp.co.sample.kintai.approval.application.CorrectionRequestService;
import jp.co.sample.kintai.approval.domain.CorrectionItem;
import jp.co.sample.kintai.approval.domain.CorrectionRequest;
import jp.co.sample.kintai.approval.domain.CorrectionRequestId;
import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockEventId;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 打刻の訂正申請（BR-09）。
 *
 * <p>決裁は<strong>すべて {@code POST} の副リソース</strong>で表す。
 * {@code PATCH} で状態を直接書かせると、
 * 「申請済 → 承認済」以外の定義していない遷移を要求できてしまう。
 */
@RestController
@RequestMapping("/api")
class CorrectionRequestController {

    private final CorrectionRequestService corrections;

    CorrectionRequestController(CorrectionRequestService corrections) {
        this.corrections = corrections;
    }

    @PostMapping("/employees/{employeeId}/correction-requests")
    @ResponseStatus(HttpStatus.CREATED)
    CorrectionRequestResponse request(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId,
            @Valid @RequestBody CorrectionRequestBody body) {
        var requester = principal.toRequester();
        CorrectionRequest created = corrections.request(requester,
                new EmployeeId(employeeId), body.workDate(),
                body.items().stream().map(CorrectionItemBody::toItem).toList(),
                body.reason());
        return respond(requester, created);
    }

    /** その社員の申請の一覧。<strong>決着したものも含む。</strong> */
    @GetMapping("/employees/{employeeId}/correction-requests")
    List<CorrectionRequestResponse> listOf(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId) {
        var requester = principal.toRequester();
        return corrections.findByEmployee(requester, new EmployeeId(employeeId)).stream()
                .map(request -> respond(requester, request)).toList();
    }

    @GetMapping("/correction-requests/{id}")
    CorrectionRequestResponse get(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                  @PathVariable UUID id) {
        var requester = principal.toRequester();
        return respond(requester,
                corrections.find(requester, new CorrectionRequestId(id)));
    }

    /** 承認待ちの一覧。<strong>見てよい社員のぶんだけ返る。</strong> */
    @GetMapping("/correction-requests/pending-approval")
    List<CorrectionRequestResponse> pendingApproval(
            @AuthenticationPrincipal AuthenticatedEmployee principal) {
        var requester = principal.toRequester();
        return corrections.findPendingApproval(requester).stream()
                .map(request -> respond(requester, request)).toList();
    }

    /**
     * 訂正を承認する。
     *
     * <p><strong>月次勤怠が下書きに戻ったことを応答に含める。</strong>
     * 伝えないと、提出済みだった月の再提出が忘れられる。
     */
    @PostMapping("/correction-requests/{id}/approval")
    CorrectionApprovalResponse approve(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody VersionBody body) {
        var requester = principal.toRequester();
        var result = corrections.approve(requester, new CorrectionRequestId(id),
                body.version());
        return new CorrectionApprovalResponse(respond(requester, result.request()),
                result.monthlyAttendanceState().name());
    }

    /** 却下する。<strong>理由が必須。</strong> */
    @PostMapping("/correction-requests/{id}/rejection")
    CorrectionRequestResponse reject(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        var requester = principal.toRequester();
        return respond(requester, corrections.reject(requester,
                new CorrectionRequestId(id), body.reason(), body.version()));
    }

    /** 取り下げる（本人）。 */
    @PostMapping("/correction-requests/{id}/cancellation")
    CorrectionRequestResponse cancel(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody VersionBody body) {
        var requester = principal.toRequester();
        return respond(requester, corrections.cancel(requester,
                new CorrectionRequestId(id), body.version()));
    }

    private CorrectionRequestResponse respond(
            jp.co.sample.kintai.shared.domain.Requester requester,
            CorrectionRequest request) {
        return CorrectionRequestResponse.from(request,
                corrections.currentVersion(requester, request.id()));
    }

    /** 申請の本文。 */
    record CorrectionRequestBody(@NotNull LocalDate workDate, @NotBlank String reason,
                                 @NotEmpty List<@Valid CorrectionItemBody> items) {
    }

    /**
     * 訂正の 1 項目。
     *
     * <p><strong>「変更」を用意しない。</strong> 取消と追加の組み合わせで表す。
     * 変更を許すと元の打刻の値が失われる。
     */
    record CorrectionItemBody(@NotNull Action action, UUID targetEventId,
                              TimeClockEvent.Type eventType, LocalDateTime occurredAt) {

        enum Action { REVOKE, ADD }

        CorrectionItem toItem() {
            return switch (action) {
                case REVOKE -> {
                    require(targetEventId != null, "REVOKE には targetEventId が要ります");
                    require(eventType == null && occurredAt == null,
                            "REVOKE に eventType / occurredAt は指定できません");
                    yield new CorrectionItem.Revoke(new TimeClockEventId(targetEventId));
                }
                case ADD -> {
                    require(eventType != null && occurredAt != null,
                            "ADD には eventType と occurredAt が要ります");
                    require(targetEventId == null,
                            "ADD に targetEventId は指定できません");
                    yield new CorrectionItem.Add(eventType.at(occurredAt));
                }
            };
        }

        /**
         * 操作ごとに必要な項目が揃っているか。
         *
         * <p>{@code @NotNull} では表せない「操作によって必要な項目が変わる」検証なので、
         * ここで行う。DB の {@code correction_items_variant_check} と同じ不変条件である。
         */
        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    /** 版だけを取る操作（承認・取下げ）。 */
    record VersionBody(@NotNull Long version) {
    }

    /** 却下の理由。<strong>空白だけも拒否する。</strong> */
    record ReasonBody(@NotBlank String reason, @NotNull Long version) {
    }

    record CorrectionRequestResponse(String id, String employeeId, String workDate,
                                     String status, String reason, long version,
                                     List<CorrectionItemResponse> items) {

        static CorrectionRequestResponse from(CorrectionRequest request, long version) {
            return new CorrectionRequestResponse(request.id().value().toString(),
                    request.employeeId().value().toString(),
                    request.workDate().toString(), request.status().name(),
                    request.reason(), version,
                    request.items().stream().map(CorrectionItemResponse::from).toList());
        }
    }

    record CorrectionItemResponse(String action, String targetEventId, String eventType,
                                  String occurredAt) {

        static CorrectionItemResponse from(CorrectionItem item) {
            return switch (item) {
                case CorrectionItem.Revoke revoke -> new CorrectionItemResponse("REVOKE",
                        revoke.targetId().value().toString(), null, null);
                case CorrectionItem.Add add -> new CorrectionItemResponse("ADD", null,
                        add.event().type().name(), add.occurredAt().toString());
            };
        }
    }

    /** 承認の結果。月次勤怠が下書きに戻ったことを含める。 */
    record CorrectionApprovalResponse(CorrectionRequestResponse request,
                                      String monthlyAttendanceStatus) {
    }
}
