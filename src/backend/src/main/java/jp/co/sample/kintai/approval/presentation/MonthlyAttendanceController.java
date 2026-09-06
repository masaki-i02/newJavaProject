package jp.co.sample.kintai.approval.presentation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jp.co.sample.kintai.approval.application.BulkClosureResult;
import jp.co.sample.kintai.approval.application.BulkClosureService;
import jp.co.sample.kintai.approval.application.MonthlyAttendanceService;
import jp.co.sample.kintai.approval.application.SubmissionResult;
import jp.co.sample.kintai.approval.domain.MonthlyAttendance;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 月次勤怠の提出・承認・締め（BR-10 / BR-11）。
 *
 * <p>遷移は<strong>すべて {@code POST} の副リソース</strong>で表す。
 * {@code PATCH /monthly-attendances/{month}} で状態を直接書かせると、
 * 「提出済 → 締め済」のような定義していない遷移を要求できてしまう。
 */
@RestController
@RequestMapping("/api")
class MonthlyAttendanceController {

    private final MonthlyAttendanceService attendances;
    private final BulkClosureService bulkClosure;

    MonthlyAttendanceController(MonthlyAttendanceService attendances,
                                BulkClosureService bulkClosure) {
        this.attendances = attendances;
        this.bulkClosure = bulkClosure;
    }

    @GetMapping("/employees/{employeeId}/monthly-attendances/{month}")
    MonthlyAttendanceResponse get(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                  @PathVariable UUID employeeId,
                                  @PathVariable YearMonth month) {
        var requester = principal.toRequester();
        long version = attendances.currentVersion(requester,
                new EmployeeId(employeeId), month);
        return attendances.find(requester, new EmployeeId(employeeId), month)
                .map(attendance -> MonthlyAttendanceResponse.from(attendance, version))
                .orElseGet(() -> MonthlyAttendanceResponse.draft(employeeId, month));
    }

    /** 承認待ちの一覧。<strong>見てよい社員のぶんだけ返る。</strong> */
    @GetMapping("/monthly-attendances/pending-approval")
    List<MonthlyAttendanceResponse> pendingApproval(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @RequestParam YearMonth month) {
        var requester = principal.toRequester();
        return attendances.findPendingApproval(requester, month).stream()
                .map(attendance -> MonthlyAttendanceResponse.from(attendance,
                        attendances.currentVersion(requester, attendance.employeeId(),
                                month)))
                .toList();
    }

    /** 誰に承認してもらうか（BR-11）。<strong>遡った経路も返す。</strong> */
    @GetMapping("/employees/{employeeId}/monthly-attendances/{month}/approver")
    ApproverResponse approver(@AuthenticationPrincipal AuthenticatedEmployee principal,
                              @PathVariable UUID employeeId, @PathVariable YearMonth month) {
        return ApproverResponse.from(attendances.approverOf(principal.toRequester(),
                new EmployeeId(employeeId), month));
    }

    @PostMapping("/employees/{employeeId}/monthly-attendances/{month}/submission")
    MonthlyAttendanceResponse submit(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable YearMonth month,
            @Valid @RequestBody SubmissionRequest request) {
        var requester = principal.toRequester();
        SubmissionResult result = attendances.submit(requester,
                new EmployeeId(employeeId), month,
                Optional.ofNullable(request.comment()), request.version());
        return respond(requester, result.attendance())
                .with(Warning.paidLeaveDateWorked(result.workedOnPaidLeaveDates()));
    }

    @PostMapping("/employees/{employeeId}/monthly-attendances/{month}/approval")
    MonthlyAttendanceResponse approve(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable YearMonth month,
            @Valid @RequestBody VersionRequest request) {
        var requester = principal.toRequester();
        return respond(requester, attendances.approve(requester,
                new EmployeeId(employeeId), month, request.version()));
    }

    /** 差し戻す。<strong>理由が必須。</strong> */
    @PostMapping("/employees/{employeeId}/monthly-attendances/{month}/rejection")
    MonthlyAttendanceResponse reject(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable YearMonth month,
            @Valid @RequestBody ReasonRequest request) {
        var requester = principal.toRequester();
        return respond(requester, attendances.reject(requester,
                new EmployeeId(employeeId), month, request.reason(), request.version()));
    }

    @PostMapping("/employees/{employeeId}/monthly-attendances/{month}/closure")
    MonthlyAttendanceResponse close(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable YearMonth month,
            @Valid @RequestBody VersionRequest request) {
        var requester = principal.toRequester();
        return respond(requester, attendances.close(requester,
                new EmployeeId(employeeId), month, request.version()));
    }

    /**
     * まとめて締める（人事）。
     *
     * <p><strong>{@code version} を取らない</strong>（API設計書 1.1）。
     * 対象が複数なので、送るべき版が 1 つに定まらない。
     * 同時実行は社員ごとに検出し、{@code skipped} の 1 件として返す。
     */
    @PostMapping("/monthly-attendances/bulk-closure")
    BulkClosureResponse closeAll(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                 @Valid @RequestBody BulkClosureRequest request) {
        return BulkClosureResponse.from(bulkClosure.closeAll(principal.toRequester(),
                request.month(),
                Optional.ofNullable(request.employeeIds())
                        .map(ids -> ids.stream().map(EmployeeId::new).toList())));
    }

    /** 承認を取り消す。<strong>理由が必須。</strong> */
    @PostMapping("/employees/{employeeId}/monthly-attendances/{month}/approval-revocation")
    MonthlyAttendanceResponse revokeApproval(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable YearMonth month,
            @Valid @RequestBody ReasonRequest request) {
        var requester = principal.toRequester();
        return respond(requester, attendances.revokeApproval(
                requester, new EmployeeId(employeeId), month,
                request.reason(), request.version()));
    }

    /**
     * 遷移した結果を返す。
     *
     * <p><strong>版は 1 つ進んでいる。</strong>
     * 続けて操作する画面が、もう一度 {@code GET} しなくてよいようにする。
     */
    private MonthlyAttendanceResponse respond(Requester requester,
                                              MonthlyAttendance attendance) {
        return MonthlyAttendanceResponse.from(attendance, attendances.currentVersion(
                requester, attendance.employeeId(), attendance.month()));
    }

    /**
     * 一括締めの依頼。
     *
     * <p>{@code employeeIds} が {@code null} なら全社員が対象。
     * <strong>空配列とは区別する。</strong> 空配列は「対象が 0 人」である。
     */
    record BulkClosureRequest(@NotNull YearMonth month, List<UUID> employeeIds) {
    }

    /** 一括締めの結果。締めた件数と、締められなかった社員を両方返す。 */
    record BulkClosureResponse(String month, int closed, List<SkippedResponse> skipped) {

        static BulkClosureResponse from(BulkClosureResult result) {
            return new BulkClosureResponse(result.month().toString(), result.closed(),
                    result.skipped().stream().map(SkippedResponse::from).toList());
        }
    }

    record SkippedResponse(String employeeId, String status, String reason) {

        static SkippedResponse from(BulkClosureResult.Skipped skipped) {
            return new SkippedResponse(skipped.employeeId().value().toString(),
                    skipped.state().name(), skipped.reason());
        }
    }

    /** 提出。{@code comment} は代理提出の理由で、本人の提出では省略できる。 */
    record SubmissionRequest(String comment, @NotNull Long version) {
    }

    /**
     * 版だけを取る操作（承認・締め）。
     *
     * <p><strong>{@code Long} にして {@code @NotNull} を付ける。</strong>
     * {@code long} だと省略されたときに 0 が入り、
     * <strong>まだ行が無い月では突き合わせが偶然通ってしまう。</strong>
     */
    record VersionRequest(@NotNull Long version) {
    }

    /**
     * 差戻し・承認の取消の理由。
     *
     * <p><strong>空白だけも拒否する。</strong>
     * {@code @NotBlank} が無いと「 」で通ってしまい、証跡に理由が残らない。
     */
    record ReasonRequest(@NotBlank String reason, @NotNull Long version) {
    }

    /** 月次勤怠の状態。 */
    /**
     * 気づかせるための情報。<strong>手続きは止めない</strong>（落とし穴 19）。
     *
     * @param type  警告の種別
     * @param dates 対象の日
     */
    record Warning(String type, List<LocalDate> dates) {

        /** 承認済みの年休の日なのに実労働がある（06 API設計書 3.5）。 */
        static Optional<Warning> paidLeaveDateWorked(List<LocalDate> dates) {
            return dates.isEmpty() ? Optional.empty()
                    : Optional.of(new Warning("paid-leave-date-worked", dates));
        }
    }

    /**
     * @param warnings 警告。<strong>遷移の応答すべてに付くわけではない</strong>ので、
     *                 無い場合は項目ごと省く。項目に {@code null} を残すと、
     *                 画面が「警告が無い」と「この操作では警告を返さない」を
     *                 区別できない（落とし穴 76）
     */
    record MonthlyAttendanceResponse(String employeeId, String month, String status,
                                     long version,
                                     @JsonInclude(JsonInclude.Include.NON_NULL)
                                     List<Warning> warnings) {

        static MonthlyAttendanceResponse from(MonthlyAttendance attendance, long version) {
            return new MonthlyAttendanceResponse(attendance.employeeId().value().toString(),
                    attendance.month().toString(), attendance.status().state().name(),
                    version, null);
        }

        /** 警告を添える。無ければそのまま返す。 */
        MonthlyAttendanceResponse with(Optional<Warning> warning) {
            return warning
                    .map(value -> new MonthlyAttendanceResponse(employeeId, month, status,
                            version, List.of(value)))
                    .orElse(this);
        }

        /**
         * まだ行が無い月。
         *
         * <p><strong>404 にしない。</strong> 「まだ何もしていない」は正常な状態であり、
         * 画面は「提出する」ボタンを出せなければならない。
         */
        static MonthlyAttendanceResponse draft(UUID employeeId, YearMonth month) {
            return new MonthlyAttendanceResponse(employeeId.toString(), month.toString(),
                    "DRAFT", 0L, null);
        }
    }
}
