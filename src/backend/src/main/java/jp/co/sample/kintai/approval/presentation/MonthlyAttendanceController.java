package jp.co.sample.kintai.approval.presentation;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import jp.co.sample.kintai.approval.application.MonthlyAttendanceService.MonthlyAttendanceView;
import jp.co.sample.kintai.approval.application.SubmissionResult;
import jp.co.sample.kintai.approval.domain.ApprovalEvent;
import jp.co.sample.kintai.approval.domain.MonthlyAttendance;
import jp.co.sample.kintai.approval.domain.MonthlyAttendanceStatus;
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

    /**
     * 月次勤怠の 1 件（[05 API設計書 2.1]）。
     *
     * <p><strong>画面が「次に何ができるか」を決めるのに要るものを全部返す。</strong>
     * 状態だけを返すと、画面が状態機械と BR-11 を複製することになる。
     */
    @GetMapping("/employees/{employeeId}/monthly-attendances/{month}")
    MonthlyAttendanceResponse get(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                  @PathVariable UUID employeeId,
                                  @PathVariable YearMonth month) {
        return MonthlyAttendanceResponse.from(attendances.view(principal.toRequester(),
                new EmployeeId(employeeId), month));
    }

    /** 承認待ちの一覧。<strong>見てよい社員のぶんだけ返る。</strong> */
    @GetMapping("/monthly-attendances/pending-approval")
    List<MonthlyAttendanceResponse> pendingApproval(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @RequestParam YearMonth month) {
        var requester = principal.toRequester();
        return attendances.findPendingApproval(requester, month).stream()
                .map(attendance -> MonthlyAttendanceResponse.summary(attendance,
                        attendances.currentVersion(requester, attendance.employeeId(),
                                month)))
                .toList();
    }

    /**
     * 締める前に見る一覧（SC-08）。
     *
     * <p><strong>名簿を軸に列挙する。</strong> 月次勤怠の行は提出時に初めて作られるので、
     * 行を読んで返すと<strong>最も知りたい「未提出」が 1 人も出ない</strong>（落とし穴 120）。
     *
     * <p><strong>版を返さない</strong>（決定表「一覧に版を載せるか」）。
     * 締めは版を取らない {@code bulk-closure} で行う。
     *
     * <p>ロールで一律に拒まない。閲覧範囲で絞れば承認者が呼んでも配下だけが返る。
     * 締められるかどうかは {@code canClose} が言う。
     */
    @GetMapping("/monthly-attendances")
    List<ClosureStatusResponse> closureStatuses(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @RequestParam YearMonth month) {
        return bulkClosure.closureStatuses(principal.toRequester(), month).stream()
                .map(status -> ClosureStatusResponse.from(status, month)).toList();
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
        // ★ 遷移したあとの版と、遷移したあとに何ができるかを読み直して返す。
        //   返さないと画面は必ず 1 回 409 を踏む（CLAUDE.md「決裁の応答の版」）
        return MonthlyAttendanceResponse.from(attendances.view(requester,
                attendance.employeeId(), attendance.month()));
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

    /**
     * 締める前の 1 行（SC-08）。
     *
     * <p><strong>{@code reason} は締められないときだけ入る。</strong>
     * 空文字を入れると「理由が無い」と「理由が空」が同じ値になる。
     *
     * <p><strong>{@code DRAFT} を「打刻が 1 件も無い」と読ませない。</strong>
     * 行が無いことが意味するのは下書きだけである（落とし穴 120）。
     */
    record ClosureStatusResponse(String employeeId, String month, String status,
                                 boolean canClose,
                                 @com.fasterxml.jackson.annotation.JsonInclude(
                                         com.fasterxml.jackson.annotation.JsonInclude
                                                 .Include.NON_NULL)
                                 String reason) {

        static ClosureStatusResponse from(BulkClosureService.ClosureStatus status,
                                          YearMonth month) {
            return new ClosureStatusResponse(status.employeeId().value().toString(),
                    month.toString(), status.state().name(),
                    status.canClose(), status.reason());
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
    /**
     * 月次勤怠の状態と、<strong>画面が次に何をできるか</strong>（[05 API設計書 2.1]）。
     *
     * <p><strong>状態機械と BR-11 を画面に複製させない。</strong>
     * {@code canSubmit} / {@code canApprove} はログイン中の利用者が実行できるかを、
     * {@code acceptsTimeClock} / {@code acceptsCorrectionRequest} はその月が
     * 打刻と訂正申請を受け付けるかを、それぞれサーバが判断して返す。
     *
     * <p><strong>この 2 つを 1 つにまとめない。</strong>
     * 提出済みは「打刻は不可・訂正申請は可」であり、まとめると
     * 本人が提出後に直接打刻できてしまう（落とし穴 57 の隣にある判断）。
     *
     * <p><strong>氏名・社員番号・部署名を返さない。</strong>
     * それらは {@code employee} が所有する概念である（設計規約チェックリスト 3）。
     *
     * <p>一覧の行では判断の項目を<strong>省く</strong>。
     * 行ごとに承認者と履歴を引くと、社員数ぶんの問い合わせが重複する
     * （CLAUDE.md「一覧に版を載せるか」と同じ理由）。
     * 省きたいのが一部の項目なので、{@code @JsonInclude} は<strong>項目ごとに</strong>付ける
     * （record 全体に付けると「所属が無い」ことまで応答から読めなくなる。落とし穴 76）。
     */
    record MonthlyAttendanceResponse(
            String employeeId, String month, String status, long version,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime submittedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String submittedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime approvedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String approvedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime closedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String closedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean acceptsTimeClock,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean acceptsCorrectionRequest,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canSubmit,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean canApprove,
            @JsonInclude(JsonInclude.Include.NON_NULL) ApproverResponse approver,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<HistoryEntry> history,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Warning> warnings) {

        /** 全遷移の 1 件。<strong>差戻しの理由を本人が読む。</strong> */
        record HistoryEntry(String eventKind, String fromStatus, String toStatus,
                            String actorId,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String comment,
                            LocalDateTime occurredAt) {

            static HistoryEntry from(ApprovalEvent event) {
                return new HistoryEntry(event.kind().name(), event.from().name(),
                        event.to().name(), event.actor().value().toString(),
                        event.comment().orElse(null), event.occurredAt());
            }
        }

        /** 詳細。判断の項目まで返す。 */
        static MonthlyAttendanceResponse from(MonthlyAttendanceView view) {
            MonthlyAttendanceStatus status = view.status();
            return new MonthlyAttendanceResponse(
                    view.employeeId().value().toString(), view.month().toString(),
                    status.state().name(), view.version(),
                    submittedAt(status), id(submittedBy(status)),
                    approvedAt(status), id(approvedBy(status)),
                    closedAt(status), id(closedBy(status)),
                    status.acceptsTimeClock(), status.acceptsCorrectionRequest(),
                    view.canSubmit(), view.canApprove(),
                    ApproverResponse.from(view.approver()),
                    view.history().stream().map(HistoryEntry::from).toList(),
                    null);
        }

        /** 一覧の行。<strong>判断の項目は省く。</strong> */
        static MonthlyAttendanceResponse summary(MonthlyAttendance attendance,
                                                 long version) {
            return new MonthlyAttendanceResponse(
                    attendance.employeeId().value().toString(),
                    attendance.month().toString(), attendance.status().state().name(),
                    version, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        /**
         * まだ行が無い月。
         *
         * <p><strong>404 にしない。</strong> 「まだ何もしていない」は正常な状態であり、
         * 画面は「提出する」ボタンを出せなければならない。
         * 版は 0（行が無いことだけを指す値。落とし穴 57）。
         */
        static MonthlyAttendanceResponse draft(UUID employeeId, YearMonth month) {
            return new MonthlyAttendanceResponse(employeeId.toString(), month.toString(),
                    "DRAFT", 0L, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        /** 警告を添える。無ければそのまま返す。 */
        MonthlyAttendanceResponse with(Optional<Warning> warning) {
            return warning
                    .map(value -> new MonthlyAttendanceResponse(employeeId, month, status,
                            version, submittedAt, submittedBy, approvedAt, approvedBy,
                            closedAt, closedBy, acceptsTimeClock, acceptsCorrectionRequest,
                            canSubmit, canApprove, approver, history, List.of(value)))
                    .orElse(this);
        }

        private static String id(EmployeeId employeeId) {
            return employeeId == null ? null : employeeId.value().toString();
        }

        private static EmployeeId submittedBy(MonthlyAttendanceStatus status) {
            return switch (status) {
                case MonthlyAttendanceStatus.Draft ignored -> null;
                case MonthlyAttendanceStatus.Submitted s -> s.submittedBy();
                case MonthlyAttendanceStatus.Approved a -> a.submittedBy();
                case MonthlyAttendanceStatus.Closed c -> c.submittedBy();
            };
        }

        private static LocalDateTime submittedAt(MonthlyAttendanceStatus status) {
            return switch (status) {
                case MonthlyAttendanceStatus.Draft ignored -> null;
                case MonthlyAttendanceStatus.Submitted s -> s.submittedAt();
                case MonthlyAttendanceStatus.Approved a -> a.submittedAt();
                case MonthlyAttendanceStatus.Closed c -> c.submittedAt();
            };
        }

        private static EmployeeId approvedBy(MonthlyAttendanceStatus status) {
            return switch (status) {
                case MonthlyAttendanceStatus.Draft ignored -> null;
                case MonthlyAttendanceStatus.Submitted ignored -> null;
                case MonthlyAttendanceStatus.Approved a -> a.approvedBy();
                case MonthlyAttendanceStatus.Closed c -> c.approvedBy();
            };
        }

        private static LocalDateTime approvedAt(MonthlyAttendanceStatus status) {
            return switch (status) {
                case MonthlyAttendanceStatus.Draft ignored -> null;
                case MonthlyAttendanceStatus.Submitted ignored -> null;
                case MonthlyAttendanceStatus.Approved a -> a.approvedAt();
                case MonthlyAttendanceStatus.Closed c -> c.approvedAt();
            };
        }

        private static EmployeeId closedBy(MonthlyAttendanceStatus status) {
            return switch (status) {
                case MonthlyAttendanceStatus.Draft ignored -> null;
                case MonthlyAttendanceStatus.Submitted ignored -> null;
                case MonthlyAttendanceStatus.Approved ignored -> null;
                case MonthlyAttendanceStatus.Closed c -> c.closedBy();
            };
        }

        private static LocalDateTime closedAt(MonthlyAttendanceStatus status) {
            return switch (status) {
                case MonthlyAttendanceStatus.Draft ignored -> null;
                case MonthlyAttendanceStatus.Submitted ignored -> null;
                case MonthlyAttendanceStatus.Approved ignored -> null;
                case MonthlyAttendanceStatus.Closed c -> c.closedAt();
            };
        }
    }
}
