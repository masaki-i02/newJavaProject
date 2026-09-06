package jp.co.sample.kintai.workrule.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;
import jp.co.sample.kintai.workrule.application.WorkRuleMasterService;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.workrule.application.WorkRuleMasterService;
import jp.co.sample.kintai.workrule.domain.DayType;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;

/**
 * カレンダーと就業規則の適用（人事）。
 *
 * <p>どちらも<strong>締め済みの月に影響する変更は 409</strong> で拒む。
 * 判定はアプリケーション層が {@code MonthClosureQuery} で行う。
 */
@RestController
@RequestMapping("/api")
class WorkRuleMasterController {

    private final WorkRuleMasterService master;

    WorkRuleMasterController(WorkRuleMasterService master) {
        this.master = master;
    }

    @PutMapping("/calendars/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setDayType(@AuthenticationPrincipal AuthenticatedEmployee principal,
                    @PathVariable LocalDate date,
                    @Valid @RequestBody CalendarBody body) {
        master.暦日区分を設定する(principal.toRequester(), date, body.dayType(),
                body.name());
    }

    /**
     * 期間の暦日区分をまとめて設定する（API設計書 3.2）。
     *
     * <p>年度ぶんを 1 日ずつ登録すると 365 回叩くことになる。
     * 割増賃金の基礎額の分母（BR-18）は年度の全日が登録されていることを要求するので、
     * この操作が無いと運用で満たせない。
     */
    @PostMapping("/calendars/bulk")
    BulkResponse setDayTypes(@AuthenticationPrincipal AuthenticatedEmployee principal,
                             @Valid @RequestBody BulkBody body) {
        // ★ 重複や期間の逆転をここで畳まない。畳むと後勝ちになり、
        //   人事は「登録したはずの祝日が入っていない」ことに気づけない（落とし穴 105）
        var result = master.暦日区分をまとめて設定する(principal.toRequester(),
                body.from(), body.toExclusive(),
                body.rules().stream()
                        .map(rule -> new WorkRuleMasterService.CalendarDayOfWeekRule(
                                rule.dayOfWeek(), rule.dayType(), rule.name()))
                        .toList(),
                body.overrides().stream()
                        .map(override -> new WorkRuleMasterService.CalendarOverride(
                                override.date(), override.dayType(), override.name()))
                        .toList());
        return BulkResponse.from(result);
    }

    @PostMapping("/employees/{employeeId}/work-rule-assignments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void assign(@AuthenticationPrincipal AuthenticatedEmployee principal,
                @PathVariable UUID employeeId,
                @Valid @RequestBody AssignmentBody body) {
        master.就業規則を適用する(principal.toRequester(), new EmployeeId(employeeId),
                new WorkRuleSeriesId(body.seriesId()), body.validFrom());
    }

    /** 一括設定。曜日の規則を当ててから、個別の日で上書きする。 */
    record BulkBody(@NotNull LocalDate from, @NotNull LocalDate toExclusive,
                    @NotNull List<BulkRule> rules, @NotNull List<BulkOverride> overrides) {
    }

    /** 曜日ごとの既定。指定の無い曜日は所定労働日。 */
    record BulkRule(@NotNull java.time.DayOfWeek dayOfWeek, @NotNull DayType dayType,
                    String name) {
    }

    /** 個別の日。曜日の規則より優先する（祝日は曜日で決まらない）。 */
    record BulkOverride(@NotNull LocalDate date, @NotNull DayType dayType, String name) {
    }

    /**
     * 一括設定の結果。
     *
     * <p>{@code warnings} は<strong>手続きを止めない知らせ</strong>である。
     * 空なら項目ごと省く（CLAUDE.md 落とし穴 76）。
     */
    record BulkResponse(int registeredCount, Map<DayType, Integer> byDayType,
                        @com.fasterxml.jackson.annotation.JsonInclude(
                                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                        List<Warning> warnings) {

        static BulkResponse from(WorkRuleMasterService.CalendarRegistration result) {
            List<Warning> warnings = result.weeksWithoutLegalHoliday().stream()
                    .map(week -> new Warning("no-legal-holiday-in-week",
                            "%s から %s の 7 日間に法定休日がありません"
                                    .formatted(week.from(), week.toExclusive().minusDays(1)),
                            new WarningPeriod(week.from(), week.toExclusive())))
                    .toList();
            return new BulkResponse(result.registeredCount(), result.byDayType(), warnings);
        }
    }

    /** 手続きを止めない知らせ。 */
    record Warning(String code, String message, WarningPeriod period) {
    }

    /** 半開区間。 */
    record WarningPeriod(LocalDate from, LocalDate toExclusive) {
    }

    /** 暦日区分。名称は祝日名などの表示用で、省略できる。 */
    record CalendarBody(@NotNull DayType dayType, String name) {
    }

    /** 就業規則の適用。<strong>系列を指す</strong>（版ではない。ADR 0003）。 */
    record AssignmentBody(@NotNull UUID seriesId, @NotNull LocalDate validFrom) {
    }
}
