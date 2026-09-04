package jp.co.sample.kintai.employee.presentation;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jp.co.sample.kintai.employee.application.DepartmentService;
import jp.co.sample.kintai.employee.application.DepartmentService.Node;
import jp.co.sample.kintai.employee.domain.Department;
import jp.co.sample.kintai.employee.domain.DepartmentCode;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 部署ツリー。
 *
 * <p><strong>一般社員には見せない</strong>（要件定義書 4.1）。
 * 誰が誰の下にいるかは人事情報であり、勤怠の記録・提出には要らない。
 */
@RestController
@RequestMapping("/api/departments")
class DepartmentController {

    private final DepartmentService departments;

    DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    DepartmentTreeResponse tree(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                @RequestParam(defaultValue = "false")
                                boolean includeAbolished) {
        return new DepartmentTreeResponse(
                departments.tree(principal.toRequester(), includeAbolished).stream()
                        .map(DepartmentNodeResponse::from).toList());
    }

    @PostMapping
    ResponseEntity<DepartmentNodeResponse> create(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @Valid @RequestBody DepartmentBody body) {
        Department created = departments.create(principal.toRequester(),
                new DepartmentCode(body.code()), body.name(),
                Optional.ofNullable(body.parentId()).map(DepartmentId::new));
        var response = DepartmentNodeResponse.leaf(created);
        return ResponseEntity.created(URI.create("/api/departments/" + response.id()))
                .body(response);
    }

    /** 名称・コード・親を更新する。<strong>廃止日はここでは扱わない。</strong> */
    @PatchMapping("/{id}")
    DepartmentNodeResponse update(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody DepartmentBody body) {
        return DepartmentNodeResponse.leaf(departments.update(principal.toRequester(),
                new DepartmentId(id), new DepartmentCode(body.code()), body.name(),
                Optional.ofNullable(body.parentId()).map(DepartmentId::new)));
    }

    /** 廃止。<strong>配下に現存する部署があれば拒む。</strong> */
    @PostMapping("/{id}/abolition")
    DepartmentNodeResponse abolish(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID id, @Valid @RequestBody AbolitionBody body) {
        return DepartmentNodeResponse.leaf(departments.abolish(principal.toRequester(),
                new DepartmentId(id), body.abolishedOn()));
    }

    /** 部署長の設定・交代。現任の期間を閉じて新しい期間を開く。 */
    @PostMapping("/{id}/managerships")
    @ResponseStatus(HttpStatus.CREATED)
    void appointManager(@AuthenticationPrincipal AuthenticatedEmployee principal,
                        @PathVariable UUID id,
                        @Valid @RequestBody ManagershipBody body) {
        departments.appointManager(principal.toRequester(), new DepartmentId(id),
                new EmployeeId(body.employeeId()), body.validFrom());
    }

    /** 登録・更新の本文。{@code parentId} が {@code null} なら根。 */
    record DepartmentBody(@NotBlank String code, @NotBlank String name, UUID parentId) {
    }

    record AbolitionBody(@NotNull LocalDate abolishedOn) {
    }

    record ManagershipBody(@NotNull UUID employeeId, @NotNull LocalDate validFrom) {
    }

    record DepartmentTreeResponse(List<DepartmentNodeResponse> departments) {
    }

    record DepartmentNodeResponse(String id, String code, String name,
                                  LocalDate abolishedOn, ManagerResponse manager,
                                  List<DepartmentNodeResponse> children) {

        static DepartmentNodeResponse from(Node node) {
            var d = node.department();
            return new DepartmentNodeResponse(d.id().value().toString(), d.code().value(),
                    d.name(), d.abolishedOn().orElse(null),
                    node.manager().map(ManagerResponse::from).orElse(null),
                    node.children().stream().map(DepartmentNodeResponse::from).toList());
        }

        /** 登録・更新の応答。<strong>この時点では子も部署長も無い。</strong> */
        static DepartmentNodeResponse leaf(Department d) {
            return new DepartmentNodeResponse(d.id().value().toString(), d.code().value(),
                    d.name(), d.abolishedOn().orElse(null), null, List.of());
        }
    }

    /** 部署長。<strong>就任日を添える。</strong> */
    record ManagerResponse(String id, String name, LocalDate since) {

        static ManagerResponse from(DepartmentService.ManagerView view) {
            return new ManagerResponse(view.employee().id().value().toString(),
                    view.employee().name(), view.since());
        }
    }
}
