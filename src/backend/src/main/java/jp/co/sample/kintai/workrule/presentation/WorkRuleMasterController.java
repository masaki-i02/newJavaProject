package jp.co.sample.kintai.workrule.presentation;

import java.time.LocalDate;
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

    @PostMapping("/employees/{employeeId}/work-rule-assignments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void assign(@AuthenticationPrincipal AuthenticatedEmployee principal,
                @PathVariable UUID employeeId,
                @Valid @RequestBody AssignmentBody body) {
        master.就業規則を適用する(principal.toRequester(), new EmployeeId(employeeId),
                new WorkRuleSeriesId(body.seriesId()), body.validFrom());
    }

    /** 暦日区分。名称は祝日名などの表示用で、省略できる。 */
    record CalendarBody(@NotNull DayType dayType, String name) {
    }

    /** 就業規則の適用。<strong>系列を指す</strong>（版ではない。ADR 0003）。 */
    record AssignmentBody(@NotNull UUID seriesId, @NotNull LocalDate validFrom) {
    }
}
