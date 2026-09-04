package jp.co.sample.kintai.employee.presentation;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jp.co.sample.kintai.employee.application.PasswordService;
import jp.co.sample.kintai.employee.domain.PasswordAttempt;
import jp.co.sample.kintai.employee.domain.RawPassword;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * パスワードの変更と再設定（API 設計書 3.10）。
 *
 * <p><strong>対象の社員をパスから受け取らない経路を分けている。</strong>
 * 本人の変更は {@code /api/me/password} で、対象は必ず認証された本人になる。
 * パスで受け取る形にすると、他人の ID を入れる経路を認可で塞ぎ続けることになる。
 */
@RestController
@RequestMapping("/api")
class PasswordController {

    private final PasswordService passwords;

    PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    /** 本人が変える。<strong>現在のパスワードを要求する。</strong> */
    @PutMapping("/me/password")
    ResponseEntity<Void> change(@AuthenticationPrincipal AuthenticatedEmployee principal,
                                @Valid @RequestBody ChangeRequest request) {
        passwords.change(principal.employeeId(),
                new PasswordAttempt(request.currentPassword()),
                new RawPassword(request.newPassword()));
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code ADMIN} が初期発行・再設定する。
     *
     * <p><strong>現在のパスワードを要求しない。</strong>
     * 本人が忘れた場合の救済経路なので、要求すると経路が成立しない。
     */
    @PutMapping("/employees/{id}/password")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Void> reset(@PathVariable UUID id, @Valid @RequestBody ResetRequest request) {
        passwords.reset(new EmployeeId(id), new RawPassword(request.newPassword()));
        return ResponseEntity.noContent().build();
    }

    record ChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    record ResetRequest(@NotBlank String newPassword) {
    }
}
