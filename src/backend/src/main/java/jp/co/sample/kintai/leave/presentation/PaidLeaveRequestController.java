package jp.co.sample.kintai.leave.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jp.co.sample.kintai.leave.application.LeaveDecisionResult;
import jp.co.sample.kintai.leave.application.PaidLeaveRequestService;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequest;
import jp.co.sample.kintai.leave.domain.PaidLeaveRequestId;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 年次有給休暇の申請・決裁・取消（BR-16）。
 *
 * <p>決裁は<strong>すべて {@code POST} の副リソース</strong>で表す（訂正申請と同じ形）。
 * {@code PATCH} で状態を直接書かせると、定義していない遷移を要求できてしまう。
 *
 * <p><strong>取下げ（{@code cancellation}）と人事の取消（{@code revocation}）を
 * 別のパスにする。</strong> 誰が行ったのかが証跡から読めなくなるのを避ける。
 */
@RestController
@RequestMapping("/api")
class PaidLeaveRequestController {

    private final PaidLeaveRequestService requests;

    PaidLeaveRequestController(PaidLeaveRequestService requests) {
        this.requests = requests;
    }

    /** 申請する。<strong>本人しか出せない</strong>（代理申請を認めない）。 */
    @PostMapping("/employees/{employeeId}/paid-leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    PaidLeaveRequestResponse submit(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId,
            @Valid @RequestBody SubmitBody body) {
        PaidLeaveRequest created = requests.submit(principal.toRequester(),
                new EmployeeId(employeeId), body.leaveDate(),
                Optional.ofNullable(body.reason()));
        return PaidLeaveRequestResponse.from(created, created.version());
    }

    /** その社員の申請。<strong>見てよいものだけ返る。</strong> */
    @GetMapping("/employees/{employeeId}/paid-leave-requests")
    List<PaidLeaveRequestResponse> listOf(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId) {
        return requests.requestsOf(principal.toRequester(), new EmployeeId(employeeId))
                .stream()
                .map(request -> PaidLeaveRequestResponse.from(request, request.version()))
                .toList();
    }

    /**
     * 承認待ちの一覧。<strong>版は載せない。</strong>
     *
     * <p>一覧で行ごとに版を引くと、社員数ぶんの問い合わせと認可判定が重なる。
     * 版が要るのは実際に決裁する 1 件だけである（API設計書 1.1）。
     */
    @GetMapping("/paid-leave-requests/pending-approval")
    List<PaidLeaveRequestResponse> pendingApproval(
            @AuthenticationPrincipal AuthenticatedEmployee principal) {
        return requests.pendingFor(principal.toRequester()).stream()
                .map(PaidLeaveRequestResponse::withoutVersion).toList();
    }

    @GetMapping("/paid-leave-requests/{id}")
    PaidLeaveRequestResponse get(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                 @PathVariable UUID id) {
        PaidLeaveRequest request = requests.find(principal.toRequester(),
                new PaidLeaveRequestId(id));
        return PaidLeaveRequestResponse.from(request, request.version());
    }

    /** 承認する（BR-11 の承認者）。 */
    @PostMapping("/paid-leave-requests/{id}/approval")
    LeaveDecisionResponse approve(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody VersionBody body) {
        return LeaveDecisionResponse.from(requests.approve(principal.toRequester(),
                new PaidLeaveRequestId(id), body.version()));
    }

    /** 却下する。<strong>理由が必須。</strong> */
    @PostMapping("/paid-leave-requests/{id}/rejection")
    LeaveDecisionResponse reject(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody CommentBody body) {
        return LeaveDecisionResponse.from(requests.reject(principal.toRequester(),
                new PaidLeaveRequestId(id), body.comment(), body.version()));
    }

    /** 取り下げる（本人）。 */
    @PostMapping("/paid-leave-requests/{id}/cancellation")
    LeaveDecisionResponse cancel(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody VersionBody body) {
        return LeaveDecisionResponse.from(requests.cancel(principal.toRequester(),
                new PaidLeaveRequestId(id), body.version()));
    }

    /** 取得日の当日以降に人事が取り消す。<strong>理由が必須。</strong> */
    @PostMapping("/paid-leave-requests/{id}/revocation")
    LeaveDecisionResponse revoke(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody CommentBody body) {
        return LeaveDecisionResponse.from(requests.revoke(principal.toRequester(),
                new PaidLeaveRequestId(id), body.comment(), body.version()));
    }

    /** 申請の本文。{@code reason} は任意（時季指定は労働者の権利である）。 */
    record SubmitBody(@NotNull LocalDate leaveDate, String reason) {
    }

    /** 版だけを取る操作（承認・取下げ）。 */
    record VersionBody(@NotNull Long version) {
    }

    /** 理由が要る操作（却下・人事の取消）。<strong>空白だけも拒否する。</strong> */
    record CommentBody(@NotBlank String comment, @NotNull Long version) {
    }

    /**
     * 申請の 1 件。
     *
     * @param grantedOn どの付与から消化したか。<strong>失効時期に直結する</strong>ので、
     *                  「残 3 日」だけでは今月末に失効するのかが分からない。
     *                  承認前は無いので項目ごと省く
     * @param version   一覧では載せない（API設計書 1.1）
     */
    record PaidLeaveRequestResponse(String id, String employeeId, String leaveDate,
                                    String status,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    String reason,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    String grantedOn,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    String comment,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    Long version) {

        static PaidLeaveRequestResponse from(PaidLeaveRequest request, long version) {
            return build(request, version, null);
        }

        static PaidLeaveRequestResponse withoutVersion(PaidLeaveRequest request) {
            return build(request, null, null);
        }

        /**
         * 決裁の応答。<strong>付与日は決裁のときだけ載せる。</strong>
         *
         * <p>一覧で行ごとに引くと、申請数ぶんの問い合わせが増える。
         * どの付与から消化したかを知りたいのは、いま決裁した 1 件だけである
         * （一覧に版を載せないのと同じ理由）。
         */
        static PaidLeaveRequestResponse decided(PaidLeaveRequest request, long version,
                                                java.time.LocalDate grantedOn) {
            return build(request, version, grantedOn);
        }

        private static PaidLeaveRequestResponse build(PaidLeaveRequest request,
                                                      Long version,
                                                      java.time.LocalDate grantedOn) {
            return new PaidLeaveRequestResponse(request.id().value().toString(),
                    request.employeeId().value().toString(),
                    request.leaveDate().toString(), request.status().name(),
                    request.reason().orElse(null),
                    grantedOn == null ? null : grantedOn.toString(),
                    request.comment().orElse(null), version);
        }
    }

    /**
     * 決裁・取消の結果。
     *
     * <p><strong>月次勤怠の状態を含める。</strong>
     * 承認・取消は提出済みの月を下書きへ戻すので、
     * 伝えないと再提出が忘れられる（訂正の承認と同じ）。
     */
    record LeaveDecisionResponse(PaidLeaveRequestResponse request,
                                 String monthlyAttendanceStatus) {

        static LeaveDecisionResponse from(LeaveDecisionResult result) {
            return new LeaveDecisionResponse(
                    PaidLeaveRequestResponse.decided(result.request(), result.version(),
                            result.allocatedGrantedOn().orElse(null)),
                    result.monthlyAttendanceState().name());
        }
    }
}
