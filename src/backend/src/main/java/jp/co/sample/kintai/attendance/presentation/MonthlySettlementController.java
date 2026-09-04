package jp.co.sample.kintai.attendance.presentation;

import java.io.Serial;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jp.co.sample.kintai.attendance.application.MonthlySettlementService;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 月次清算の API（API 設計書 2・3）。
 *
 * <p>閲覧範囲（本人 / 上長 / 人事）の判定は {@code application} 層が行う。
 * 「配下部署の社員か」は組織の状態に依存する業務判断なので、
 * ここでも Spring Security の設定でも表現できない。
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/settlements")
public class MonthlySettlementController {

    private final MonthlySettlementService settlements;

    public MonthlySettlementController(MonthlySettlementService settlements) {
        this.settlements = settlements;
    }

    /**
     * その月の清算結果。
     *
     * <p><strong>まだ計算されていない月は 404 である。</strong>
     * ここで暗黙に計算して返すと、参照のつもりの操作が値を書き換える。
     */
    @GetMapping("/{month}")
    public MonthlySettlementResponse get(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable YearMonth month) {
        var id = new EmployeeId(employeeId);
        return settlements.find(principal.toRequester(), id, month)
                .map(settlement -> MonthlySettlementResponse.from(settlement,
                        settlements.currentVersion(id, month)))
                .orElseThrow(() -> new SettlementNotFoundException(month));
    }

    /**
     * 計算し直す（{@code HR} のみ）。
     *
     * <p>ロールの検査は {@code application} 層にも置いてある。
     * <strong>この層の注釈だけに頼らない。</strong>
     * 別の入口（バッチ・他のコントローラ）から呼ばれたときに素通りする。
     */
    @PostMapping("/{month}/recalculation")
    public MonthlySettlementResponse recalculate(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable YearMonth month,
            @Valid @RequestBody RecalculationRequest request) {
        var id = new EmployeeId(employeeId);
        var settlement = settlements.recalculate(principal.toRequester(), id, month,
                request.version());
        return MonthlySettlementResponse.from(settlement,
                settlements.currentVersion(id, month));
    }

    /**
     * 再計算の要求。
     *
     * @param version 画面が表示していた版。<strong>これが一致しないと再計算しない。</strong>
     *                その間に別の経路で値が変わっていたら、
     *                人事が見ていない結果を上書きすることになる
     */
    record RecalculationRequest(@PositiveOrZero long version) {
    }

    /**
     * まだ計算されていない。
     *
     * <p>打刻が 1 件も無い月・提出前の月では<strong>正常に起こりうる。</strong>
     */
    static final class SettlementNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        SettlementNotFoundException(YearMonth month) {
            super("月次清算が見つかりません: " + month);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:resource-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "月次清算が見つかりません";
        }
    }
}
