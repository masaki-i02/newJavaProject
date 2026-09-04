package jp.co.sample.kintai.employee.presentation;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jp.co.sample.kintai.employee.application.EmployeeDirectoryService;
import jp.co.sample.kintai.employee.application.EmployeeDirectoryService.EmployeeWithDepartment;
import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.employee.domain.Email;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 社員名簿（要件定義書 4.1）。
 *
 * <p>閲覧の範囲はロールによって変わるが、<strong>その判定はここでは行わない。</strong>
 * 「自分が長を務める部署の配下か」は組織に依存する業務判断であり、
 * {@code application} 層の責務である。
 */
@RestController
@RequestMapping("/api/employees")
class EmployeeController {

    private final EmployeeDirectoryService directory;

    EmployeeController(EmployeeDirectoryService directory) {
        this.directory = directory;
    }

    /**
     * 社員一覧。
     *
     * <p><strong>配列を裸で返さずオブジェクトで包む。</strong>
     * 一度 {@code [...]} で公開すると、後からページングのメタ情報を足せない。
     */
    @GetMapping
    EmployeeListResponse list(@AuthenticationPrincipal AuthenticatedEmployee principal,
                              @RequestParam Optional<LocalDate> date,
                              @RequestParam Optional<UUID> departmentId,
                              @RequestParam(defaultValue = "false") boolean includeRetired) {
        var requester = principal.toRequester();
        // 一覧には版を載せない。行ごとに引くと社員数ぶんの問い合わせと
        // 認可判定が重複する。版が要るのは更新する 1 人だけなので詳細で返す
        List<EmployeeResponse> rows = directory.list(requester, date,
                        departmentId.map(DepartmentId::new), includeRetired).stream()
                .map(row -> EmployeeResponse.from(row, null))
                .toList();
        return new EmployeeListResponse(rows);
    }

    @GetMapping("/{id}")
    EmployeeResponse get(@AuthenticationPrincipal AuthenticatedEmployee principal,
                         @PathVariable UUID id,
                         @RequestParam Optional<LocalDate> date) {
        var requester = principal.toRequester();
        var row = directory.find(requester, new EmployeeId(id), date);
        return EmployeeResponse.from(row, version(requester, row));
    }

    /** 社員を登録する。<strong>所属も同時に作る。</strong> */
    @PostMapping
    ResponseEntity<EmployeeResponse> register(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @Valid @RequestBody RegistrationBody body) {
        var requester = principal.toRequester();
        var row = directory.register(requester, new EmployeeNumber(body.employeeNumber()),
                body.name(), new Email(body.email()), body.hiredOn(),
                new DepartmentId(body.departmentId()),
                Optional.ofNullable(body.additionalRoles()).orElse(Set.of()));
        var response = EmployeeResponse.from(row, version(requester, row));
        return ResponseEntity.created(URI.create("/api/employees/" + response.id()))
                .body(response);
    }

    /** 氏名とメールアドレスだけを更新する。 */
    @PatchMapping("/{id}")
    EmployeeResponse update(@AuthenticationPrincipal AuthenticatedEmployee principal,
                            @PathVariable UUID id,
                            @Valid @RequestBody UpdateBody body) {
        var requester = principal.toRequester();
        var row = directory.update(requester, new EmployeeId(id), body.name(),
                new Email(body.email()), body.version());
        return EmployeeResponse.from(row, version(requester, row));
    }

    private Long version(Requester requester, EmployeeWithDepartment row) {
        return directory.currentVersion(requester, row.employee().id());
    }

    /**
     * 登録の本文。
     *
     * <p><strong>{@code additionalRoles} という名前にする。</strong>
     * {@code EMPLOYEE} はサーバが無条件に付与するので、
     * 指定できるのは追加のロールだけであることを名前で示す（要件定義書 4 章）。
     */
    record RegistrationBody(@NotBlank String employeeNumber, @NotBlank String name,
                            @NotBlank String email, @NotNull LocalDate hiredOn,
                            @NotNull UUID departmentId, Set<Role> additionalRoles) {
    }

    /** 更新の本文。<strong>社員番号・入社日・退職日・ロールは含めない。</strong> */
    record UpdateBody(@NotBlank String name, @NotBlank String email,
                      @NotNull Long version) {
    }

    record EmployeeListResponse(List<EmployeeResponse> employees) {
    }

    /**
     * 社員。
     *
     * <p>{@code version} は詳細でだけ返す。
     * 一覧で行ごとに引くと、社員数ぶんの問い合わせと認可判定が重複する。
     */
    record EmployeeResponse(String id, String employeeNumber, String name, String email,
                            LocalDate hiredOn, LocalDate retiredOn, List<String> roles,
                            DepartmentResponse department,
                            // ★ 省略するのは version だけ。record 全体に付けると
                            //   department と retiredOn の null まで消え、
                            //   「所属が無い」ことを応答から読み取れなくなる
                            @com.fasterxml.jackson.annotation.JsonInclude(
                                    com.fasterxml.jackson.annotation.JsonInclude
                                            .Include.NON_NULL)
                            Long version) {

        static EmployeeResponse from(EmployeeWithDepartment row, Long version) {
            var employee = row.employee();
            return new EmployeeResponse(employee.id().value().toString(),
                    employee.number().value(), employee.name(), employee.email().value(),
                    employee.hiredOn(), employee.retiredOn().orElse(null),
                    employee.roles().stream().map(Role::name).sorted().toList(),
                    row.department().map(DepartmentResponse::from).orElse(null),
                    version);
        }
    }

    /** 所属。<strong>未来日入社の社員では {@code null} になる。</strong> */
    record DepartmentResponse(String id, String code, String name) {

        static DepartmentResponse from(jp.co.sample.kintai.employee.domain.Department d) {
            return new DepartmentResponse(d.id().value().toString(), d.code().value(),
                    d.name());
        }
    }
}
