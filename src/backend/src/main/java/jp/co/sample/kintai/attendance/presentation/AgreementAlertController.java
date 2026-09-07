package jp.co.sample.kintai.attendance.presentation;

import java.time.YearMonth;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.attendance.application.MonthlySettlementService.AgreementAlert;
import jp.co.sample.kintai.attendance.application.MonthlySettlementService.AlertType;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 36 協定の超過者一覧（04 API 設計書 4）。
 *
 * <p><strong>社員番号・氏名・部署を返さない。</strong>
 * それらは {@code employee} が所有する概念であり、
 * {@code attendance} の応答に混ぜると、こちらが持っていない情報の提供者になる
 * （設計規約チェックリスト 3）。
 * 画面は {@code GET /api/employees?ids=...} で氏名と所属を引く。
 */
@RestController
@RequestMapping("/api/settlements")
public class AgreementAlertController {

    private final MonthlySettlementService settlements;

    public AgreementAlertController(MonthlySettlementService settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/agreement-alerts")
    public AgreementAlertsResponse alerts(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @RequestParam YearMonth month,
            @RequestParam(required = false) AlertType type) {
        List<AgreementAlert> found = settlements.agreementAlerts(principal.toRequester(),
                month, type == null ? AlertType.ALL : type);
        return AgreementAlertsResponse.of(month, found);
    }

    /** 超過者の一覧と件数。 */
    public record AgreementAlertsResponse(String month, List<Alert> alerts, Summary summary) {

        static AgreementAlertsResponse of(YearMonth month, List<AgreementAlert> alerts) {
            return new AgreementAlertsResponse(month.toString(),
                    alerts.stream().map(Alert::of).toList(),
                    new Summary(
                            (int) alerts.stream().filter(a -> a.usage().exceedsMonthly()).count(),
                            (int) alerts.stream().filter(a -> a.usage().exceedsAnnual()).count()));
        }
    }

    /**
     * 超過している社員 1 人ぶん。
     *
     * <p><strong>限度時間の対象は時間外労働だけ</strong>で、休日労働を含まない
     * （36 条 3 項。CLAUDE.md 落とし穴 52）。
     * だから {@code subjectMinutes} は法定休日労働を足していない。
     */
    public record Alert(String employeeId, long subjectMinutes, long monthlyLimitMinutes,
                        boolean exceedsMonthly, long annualUsedBeforeMinutes,
                        long annualLimitMinutes, boolean exceedsAnnual) {

        static Alert of(AgreementAlert alert) {
            var usage = alert.usage();
            return new Alert(alert.employeeId().value().toString(),
                    usage.subjectTime().toMinutes(),
                    usage.monthlyLimit().toMinutes(), usage.exceedsMonthly(),
                    usage.annualUsedBefore().toMinutes(),
                    usage.annualLimit().toMinutes(), usage.exceedsAnnual());
        }
    }

    /** 件数。画面が「何人いるか」を一目で出せるようにする。 */
    public record Summary(int monthlyExceeded, int annualExceeded) {
    }
}
