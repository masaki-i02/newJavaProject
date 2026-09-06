package jp.co.sample.kintai.leave.presentation;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jp.co.sample.kintai.leave.application.GrantResult;
import jp.co.sample.kintai.leave.application.PaidLeaveGrantService;
import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.leave.domain.PaidLeaveGrant;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 年次有給休暇の付与（BR-14）。
 *
 * <p><strong>{@code POST} で行う。</strong> {@code GET} で付与すると、
 * 参照しただけで行が作られ、作った人も理由も残らない。
 *
 * <p>日次バッチ（{@code @Scheduled}）は<strong>同じユースケースをそのまま呼ぶ</strong>。
 * 手順を 2 か所に書かない（落とし穴 67）。
 */
@RestController
@RequestMapping("/api")
class PaidLeaveGrantController {

    private final PaidLeaveGrantService grants;

    PaidLeaveGrantController(PaidLeaveGrantService grants) {
        this.grants = grants;
    }

    /**
     * 基準日までに到来した未処理の付与を作る（人事）。
     *
     * <p><strong>{@code asOf} の既定値は application 層が {@code Clock} から解決する</strong>
     * （AR-09）。ここで {@code LocalDate.now()} を呼ぶとテストで固定できない。
     */
    @PostMapping("/paid-leave-grants")
    GrantResultResponse grant(@AuthenticationPrincipal AuthenticatedEmployee principal,
                              @Valid @RequestBody(required = false) GrantBody body) {
        Optional<LocalDate> asOf = Optional.ofNullable(body).map(GrantBody::asOf);
        return GrantResultResponse.from(grants.grantAsOf(principal.toRequester(), asOf));
    }

    /**
     * 出勤率を判定し直す（人事）。
     *
     * <p>使う場面は 2 つ。訂正申請で欠勤が出勤に直った場合と、
     * <strong>休業（労災・産前産後・育児介護）を出勤扱いとして申告する場合</strong>である。
     */
    @PostMapping("/employees/{employeeId}/paid-leave-grants/{grantedOn}/reassessment")
    ReassessmentResponse reassess(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate grantedOn,
            @Valid @RequestBody(required = false) ReassessmentBody body) {
        int deemedDays = body == null || body.deemedAttendedDays() == null
                ? 0 : body.deemedAttendedDays();
        String reason = body == null ? null : body.deemedReason();
        return ReassessmentResponse.from(grants.reassess(principal.toRequester(),
                new EmployeeId(employeeId), grantedOn, deemedDays, reason));
    }

    /**
     * 付与の基準日。省略すると当日。
     *
     * <p><strong>{@code @DateTimeFormat} は付けない。</strong>
     * 本文は Jackson が読むのでこの注釈は効かず、
     * 「この注釈が形式を保証している」という誤読を招く。
     * 日付の書式は {@code spring.mvc.format.date: iso} と Jackson の既定が担う。
     */
    record GrantBody(LocalDate asOf) {
    }

    /**
     * 出勤扱いの申告。
     *
     * @param deemedAttendedDays 省略すると 0（実績だけで判定し直す）。
     *                           1 以上を渡すなら {@code deemedReason} は必須である
     */
    record ReassessmentBody(@PositiveOrZero Integer deemedAttendedDays,
                            String deemedReason) {

        /**
         * 出勤扱いを申告するなら理由が要る（BR-14）。
         *
         * <p><strong>{@code @NotBlank} では表せない。</strong>
         * 必要かどうかが別の項目の値で変わるので、ここで判定する。
         * すり抜けた場合は {@code application} 層が 422 で受ける
         * （{@code DeemedAttendanceRejectedException}）。
         */
        @jakarta.validation.constraints.AssertTrue(
                message = "出勤扱いの日数を申告するときは理由が必要です")
        boolean isDeemedReasonPresentWhenNeeded() {
            return deemedAttendedDays == null || deemedAttendedDays == 0
                    || (deemedReason != null && !deemedReason.isBlank());
        }
    }

    /**
     * 付与の実行結果。
     *
     * <p><strong>4 つに分ける。</strong> 付与・不付与（法どおり）・処理済み・失敗は、
     * 人事が取るべき行動がそれぞれ違う（落とし穴 60）。
     */
    record GrantResultResponse(String asOf, List<GrantedResponse> granted,
                               List<WithheldResponse> withheld,
                               List<SkippedResponse> skipped,
                               List<FailedResponse> failed) {

        static GrantResultResponse from(GrantResult result) {
            return new GrantResultResponse(result.asOf().toString(),
                    result.granted().stream().map(GrantedResponse::from).toList(),
                    result.withheld().stream().map(WithheldResponse::from).toList(),
                    result.skipped().stream().map(SkippedResponse::from).toList(),
                    result.failed().stream().map(FailedResponse::from).toList());
        }
    }

    record GrantedResponse(String employeeId, String grantedOn, int days) {

        static GrantedResponse from(GrantResult.Granted granted) {
            return new GrantedResponse(granted.employeeId().value().toString(),
                    granted.grantedOn().toString(), granted.days());
        }
    }

    /** 8 割未達で付与しなかった。<strong>判定の根拠を返す。</strong> */
    record WithheldResponse(String employeeId, String grantedOn,
                            GrantResponse.AttendanceRateResponse attendanceRate) {

        static WithheldResponse from(GrantResult.Withheld withheld) {
            return new WithheldResponse(withheld.employeeId().value().toString(),
                    withheld.grantedOn().toString(),
                    GrantResponse.AttendanceRateResponse.from(withheld.rate()));
        }
    }

    record SkippedResponse(String employeeId, String grantedOn, String reason) {

        static SkippedResponse from(GrantResult.Skipped skipped) {
            return new SkippedResponse(skipped.employeeId().value().toString(),
                    skipped.grantedOn().toString(), skipped.reason());
        }
    }

    /** 計算そのものが失敗した。<strong>例外の種別だけ</strong>を返す。 */
    record FailedResponse(String employeeId, String reason) {

        static FailedResponse from(GrantResult.Failed failed) {
            return new FailedResponse(failed.employeeId().value().toString(),
                    failed.reason());
        }
    }

    /** 再判定の結果。付与に変わった場合も、不付与のままの場合も同じ形で返す。 */
    record ReassessmentResponse(String employeeId, String grantedOn, boolean granted,
                                @com.fasterxml.jackson.annotation.JsonInclude(
                                        com.fasterxml.jackson.annotation.JsonInclude
                                                .Include.NON_NULL)
                                Integer days,
                                GrantResponse.AttendanceRateResponse attendanceRate) {

        static ReassessmentResponse from(PaidLeaveGrant grant) {
            AttendanceRate rate = grant.rate();
            return new ReassessmentResponse(grant.employeeId().value().toString(),
                    grant.grantedOn().toString(), grant.isGranted(),
                    grant.isGranted() ? grant.days() : null,
                    GrantResponse.AttendanceRateResponse.from(rate));
        }
    }
}
