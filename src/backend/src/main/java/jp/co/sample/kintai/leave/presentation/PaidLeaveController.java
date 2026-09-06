package jp.co.sample.kintai.leave.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sample.kintai.leave.application.ObligationList;
import jp.co.sample.kintai.leave.application.ObligationSummary;
import jp.co.sample.kintai.leave.application.PaidLeaveBalanceService;
import jp.co.sample.kintai.leave.application.PaidLeaveSummary;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 残日数と年 5 日の取得義務の参照（BR-15 / BR-17）。
 *
 * <p><strong>基準日の既定値をここで決めない。</strong>
 * {@code application} 層が {@code Clock} から解決する（AR-09）。
 * コントローラで {@code LocalDate.now()} を呼ぶと、テストで時刻を固定できなくなる。
 */
@RestController
@RequestMapping("/api")
class PaidLeaveController {

    private final PaidLeaveBalanceService balances;

    PaidLeaveController(PaidLeaveBalanceService balances) {
        this.balances = balances;
    }

    /** 残日数・付与の内訳・年 5 日の状況。<strong>閲覧範囲は application が判定する。</strong> */
    @GetMapping("/employees/{employeeId}/paid-leave")
    PaidLeaveResponse balance(@AuthenticationPrincipal AuthenticatedEmployee principal,
                              @PathVariable UUID employeeId,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                              LocalDate asOf) {
        return PaidLeaveResponse.from(balances.summaryOf(principal.toRequester(),
                new EmployeeId(employeeId), Optional.ofNullable(asOf)));
    }

    /**
     * 年 5 日が未達の社員（BR-17）。
     *
     * <p><strong>配列を裸で返さずオブジェクトで包む。</strong>
     * 後から項目を足すときに、応答の形を壊さずに済む。
     */
    @GetMapping("/paid-leave/obligations")
    ObligationsResponse obligations(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "true") boolean onlyShortfall) {
        return ObligationsResponse.from(balances.obligationsFor(principal.toRequester(),
                Optional.ofNullable(asOf), onlyShortfall));
    }

    record ObligationsResponse(String asOf, List<ObligationItemResponse> items) {

        static ObligationsResponse from(ObligationList list) {
            return new ObligationsResponse(list.asOf().toString(),
                    list.items().stream().map(ObligationItemResponse::from).toList());
        }
    }

    record ObligationItemResponse(String employeeId, String grantedOn, String deadline,
                                  int requiredDays, int takenDays, int shortfallDays,
                                  int remainingDaysUntilDeadline) {

        static ObligationItemResponse from(ObligationSummary summary) {
            var obligation = summary.obligation();
            return new ObligationItemResponse(summary.employeeId().value().toString(),
                    obligation.grantedOn().toString(), obligation.deadline().toString(),
                    jp.co.sample.kintai.leave.domain.AnnualObligation.REQUIRED_DAYS,
                    obligation.takenDays(), obligation.shortfallDays(),
                    summary.remainingDaysUntilDeadline());
        }
    }

    /** 残日数の照会結果。 */
    record PaidLeaveResponse(String employeeId, String asOf, int remainingDays,
                             int availableDays, List<GrantResponse> grants,
                             List<ObligationResponse> obligations) {

        static PaidLeaveResponse from(PaidLeaveSummary summary) {
            return new PaidLeaveResponse(summary.employeeId().value().toString(),
                    summary.asOf().toString(), summary.remainingDays(),
                    summary.availableDays(),
                    summary.grants().stream()
                            .map(grant -> GrantResponse.from(grant, summary)).toList(),
                    summary.obligations().stream().map(ObligationResponse::from).toList());
        }
    }

    /** 付与の 1 件。<strong>年 5 日の期限は義務の側だけが持つ。</strong> */
    record ObligationResponse(String grantedOn, String deadline, int requiredDays,
                              int takenDays, int shortfallDays) {

        static ObligationResponse from(
                jp.co.sample.kintai.leave.domain.AnnualObligation obligation) {
            return new ObligationResponse(obligation.grantedOn().toString(),
                    obligation.deadline().toString(),
                    jp.co.sample.kintai.leave.domain.AnnualObligation.REQUIRED_DAYS,
                    obligation.takenDays(), obligation.shortfallDays());
        }
    }
}
