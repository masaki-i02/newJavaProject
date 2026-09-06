package jp.co.sample.kintai.payroll.presentation;

import java.net.URI;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jp.co.sample.kintai.payroll.application.PayrollExportService;
import jp.co.sample.kintai.payroll.domain.PayrollExport;
import jp.co.sample.kintai.payroll.domain.PayrollExportId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;
import jp.co.sample.kintai.workrule.application.WorkRuleMasterService;
import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;

/**
 * 給与連携の API（API設計書・BR-18）。
 *
 * <p><strong>2 段階に分ける。</strong>
 * {@code POST} が記録を残して除外した社員を返し、{@code GET} が CSV を返す。
 * {@code text/csv} の本文に除外の一覧は載せられず、
 * JSON に CSV を埋めると取込側がまず文字列を剥がすことになる。
 *
 * <p>記録は {@code POST} の時点で残す。{@code GET} を待つと、
 * 作ったのに取得しなかった実行が記録されない。
 */
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {

    private final PayrollExportService exports;
    private final WorkRuleMasterService workRules;

    public PayrollController(PayrollExportService exports, WorkRuleMasterService workRules) {
        this.exports = exports;
        this.workRules = workRules;
    }

    /** 給与連携データを作り、記録を残す。 */
    @PostMapping("/exports")
    public ResponseEntity<PayrollExportResponse> create(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @Valid @RequestBody CreateExportRequest request) {
        Requester requester = principal.toRequester();
        PayrollExport export = exports.export(requester, request.month());
        var response = PayrollExportResponse.from(export,
                exports.employeesOf(requester, export));
        return ResponseEntity
                .created(URI.create("/api/payroll/exports/" + export.id().value()))
                .body(response);
    }

    /**
     * 作った内容を CSV で受け取る。
     *
     * <p>本文は保存していない。記録に残した<strong>対象社員</strong>から作り直す。
     * 締め済みの値は動かないので、何度取得しても同じ内容になる。
     */
    @GetMapping(value = "/exports/{id}", produces = "text/csv; charset=utf-8")
    public ResponseEntity<byte[]> content(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id) {
        byte[] csv = PayrollCsv.render(
                exports.rowsOf(principal.toRequester(), new PayrollExportId(id)));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"payroll-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }

    /** 出力の記録を照会する（監査）。 */
    @GetMapping("/exports")
    public ExportListResponse list(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int limit) {
        List<PayrollExportSummaryResponse> found = exports
                .list(principal.toRequester(), Optional.ofNullable(month), limit).stream()
                .map(PayrollExportSummaryResponse::from)
                .toList();
        return new ExportListResponse(found);
    }

    /** 年間の所定と 1 か月平均所定労働時間数（労基則 19 条 1 項 4 号）。 */
    @GetMapping("/scheduled-hours/{fiscalYear}")
    public AnnualScheduledHoursResponse scheduledHours(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable int fiscalYear) {
        AnnualScheduledHours annual =
                workRules.annualScheduledHours(principal.toRequester(), fiscalYear);
        return AnnualScheduledHoursResponse.from(annual);
    }

    /** 出力の依頼。 */
    public record CreateExportRequest(@NotNull YearMonth month) {
    }

    /** 出力の記録の一覧。 */
    public record ExportListResponse(List<PayrollExportSummaryResponse> exports) {
    }
}
