package jp.co.sample.kintai.employee.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jp.co.sample.kintai.employee.application.EmployeeLifecycleService;
import jp.co.sample.kintai.employee.application.EmployeeLifecycleService.AssignmentWithDepartment;
import jp.co.sample.kintai.employee.application.EmployeeLifecycleService.RetirementResult;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 異動・退職・ロール（{@code ADMIN}）。
 *
 * <p>退職は<strong>副作用を伴う操作</strong>なので、
 * {@code PATCH /api/employees/{id}} で {@code retiredOn} を書かせない。
 * 専用の副リソースにして、何が起きるかを URL で示す。
 */
@RestController
@RequestMapping("/api/employees/{id}")
class EmployeeLifecycleController {

    private final EmployeeLifecycleService lifecycle;

    EmployeeLifecycleController(EmployeeLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** 所属履歴。<strong>新しい順。</strong> {@code validTo} が {@code null} は現在の所属。 */
    @GetMapping("/assignments")
    AssignmentListResponse assignments(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id) {
        return new AssignmentListResponse(
                lifecycle.history(principal.toRequester(), new EmployeeId(id)).stream()
                        .map(AssignmentResponse::from).toList());
    }

    /** 異動。現在の所属を閉じて新しい所属を開く。 */
    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    void transfer(@AuthenticationPrincipal AuthenticatedEmployee principal,
                  @PathVariable UUID id, @Valid @RequestBody TransferBody body) {
        lifecycle.transfer(principal.toRequester(), new EmployeeId(id),
                new DepartmentId(body.departmentId()), body.validFrom());
    }

    /** 退職の登録。<strong>所属と部署長を退職日の翌日で閉じる。</strong> */
    @PostMapping("/retirement")
    RetirementResponse retire(@AuthenticationPrincipal AuthenticatedEmployee principal,
                              @PathVariable UUID id,
                              @Valid @RequestBody RetirementBody body) {
        return RetirementResponse.from(lifecycle.retire(principal.toRequester(),
                new EmployeeId(id), body.retiredOn(), body.version()));
    }

    /**
     * 退職の取消。<strong>誤登録の訂正にだけ使う。</strong>
     *
     * <p><strong>版は本文ではなくクエリパラメータで受ける。</strong>
     * RFC 9110 は {@code DELETE} の本文に意味を定めていない。
     * 中継するプロキシや HTTP クライアントに落とされることがあり、
     * <strong>版が届かないまま既定値で通ってしまう</strong>のが最悪の壊れ方である。
     */
    @DeleteMapping("/retirement")
    RetirementResponse cancelRetirement(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @RequestParam long version) {
        return RetirementResponse.from(lifecycle.cancelRetirement(
                principal.toRequester(), new EmployeeId(id), version));
    }

    /**
     * ロールの付与・剥奪。
     *
     * <p><strong>置き換えである。</strong> 送られた集合がそのロールの全体になる。
     * 差分を送らせると、同時に 2 人が変更したときに片方が消える。
     */
    @PutMapping("/roles")
    List<String> changeRoles(@AuthenticationPrincipal AuthenticatedEmployee principal,
                             @PathVariable UUID id,
                             @Valid @RequestBody RolesBody body) {
        return lifecycle.changeRoles(principal.toRequester(), new EmployeeId(id),
                        body.roles(), body.version())
                .roles().stream().map(Role::name).sorted().toList();
    }

    record TransferBody(@NotNull UUID departmentId, @NotNull LocalDate validFrom) {
    }

    record RetirementBody(@NotNull LocalDate retiredOn, @NotNull Long version) {
    }

    /** {@code EMPLOYEE} は送らなくてもサーバが付ける。{@code APPROVER} は送れない。 */
    record RolesBody(@NotNull Set<Role> roles, @NotNull Long version) {
    }

    record AssignmentListResponse(List<AssignmentResponse> assignments) {
    }

    record AssignmentResponse(String departmentId, String code, String name,
                              LocalDate validFrom, LocalDate validTo) {

        static AssignmentResponse from(AssignmentWithDepartment row) {
            var period = row.assignment().period();
            return new AssignmentResponse(row.department().id().value().toString(),
                    row.department().code().value(), row.department().name(),
                    period.from(),
                    // ★ 番兵は外へ出さない。開いている期間は null で表す
                    period.isUnbounded() ? null : period.toExclusive());
        }
    }

    /** 退職の登録・取消の結果。閉じた（開き直した）件数を返す。 */
    record RetirementResponse(LocalDate retiredOn, int closedAssignments,
                              int closedManagerships) {

        static RetirementResponse from(RetirementResult result) {
            return new RetirementResponse(result.retiredOn(), result.assignments(),
                    result.managerships());
        }
    }
}
