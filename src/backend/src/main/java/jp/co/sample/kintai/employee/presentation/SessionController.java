package jp.co.sample.kintai.employee.presentation;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jp.co.sample.kintai.employee.application.SignInService;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.PasswordAttempt;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * ログインとログアウト（API 設計書 3.9）。
 *
 * <p>Spring Security の既定のフォームログインを使わない。
 * <strong>「有効なロール」の導出が業務判断だから</strong>である（{@code APPROVER} は
 * その日に部署長を務めているかで決まる）。
 * 認証そのものを {@code application} 層のユースケースとして書き、
 * ここは結果をセッションへ載せるだけにする。
 */
@RestController
@RequestMapping("/api")
class SessionController {

    private final SignInService signIn;
    private final SecurityContextRepository securityContextRepository;

    SessionController(SignInService signIn,
                      SecurityContextRepository securityContextRepository) {
        this.signIn = signIn;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/sessions")
    ResponseEntity<MeResponse> create(@Valid @RequestBody SignInRequest request,
                                      HttpServletRequest httpRequest,
                                      HttpServletResponse httpResponse) {
        SignInService.SignedIn signedIn = signIn.signIn(
                new EmployeeNumber(request.employeeNumber()),
                new PasswordAttempt(request.password()));

        var principal = new AuthenticatedEmployee(signedIn.employee().id(),
                signedIn.employee().number().value(), signedIn.employee().name(),
                signedIn.roles());

        // ★ セッションを作り直す。使い回すとセッション固定攻撃が成立する
        httpRequest.changeSessionId();

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return ResponseEntity.ok(MeResponse.of(principal));
    }

    @DeleteMapping("/sessions")
    ResponseEntity<Void> delete(HttpServletRequest httpRequest) {
        var session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal AuthenticatedEmployee principal) {
        return MeResponse.of(principal);
    }

    /**
     * ログインの入力。
     *
     * <p>空かどうかだけを見る。<strong>強度は検証しない。</strong>
     * ここで規則（BR-13）を当てると、短いパスワードを入力しただけで
     * 「パスワードが規則を満たしていません（422）」が返り、
     * 認証の失敗理由を区別して返さないという決めごとが崩れる。
     */
    record SignInRequest(@NotBlank String employeeNumber, @NotBlank String password) {
    }

    /** ログイン中の社員（API 設計書 3.1）。 */
    record MeResponse(String id, String employeeNumber, String name, List<String> roles) {

        static MeResponse of(AuthenticatedEmployee principal) {
            return new MeResponse(principal.employeeId().value().toString(),
                    principal.employeeNumber(), principal.name(),
                    principal.roles().stream().map(Enum::name).sorted().toList());
        }
    }
}
