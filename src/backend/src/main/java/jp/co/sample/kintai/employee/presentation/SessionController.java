package jp.co.sample.kintai.employee.presentation;

import java.time.LocalDate;
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
import jp.co.sample.kintai.employee.application.EmployeeDirectoryService;
import jp.co.sample.kintai.employee.application.EmployeeDirectoryService.EmployeeWithDepartment;
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
    private final EmployeeDirectoryService directory;
    private final SecurityContextRepository securityContextRepository;

    SessionController(SignInService signIn, EmployeeDirectoryService directory,
                      SecurityContextRepository securityContextRepository) {
        this.signIn = signIn;
        this.directory = directory;
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

        // ★ セッションを作り直す。使い回すとセッション固定攻撃が成立する。
        //   ただし「作り直す」には作り直す相手が要る。
        //   まっさらなブラウザからの最初のログインにはセッションが無く、
        //   changeSessionId() は IllegalStateException を投げる（＝ 500）。
        //   CSRF トークンはクッキーに持たせている（CookieCsrfTokenRepository）ので
        //   セッションを必要とせず、ここまでで作られていない。
        //   MockMvc のテストは要求ごとにセッションを用意するため、この経路を通らない。
        if (httpRequest.getSession(false) == null) {
            httpRequest.getSession(true);
        } else {
            httpRequest.changeSessionId();
        }

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // ★ ログインの応答も `GET /api/me` と同じ形にする。
        //   形が違うと、画面はログイン直後だけ所属を持たない状態になる
        return ResponseEntity.ok(MeResponse.of(principal,
                directory.find(principal.toRequester(), principal.employeeId(),
                        java.util.Optional.empty())));
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

    /**
     * ログイン中の社員（API 設計書 3.1）。
     *
     * <p><strong>所属を含める。</strong> 一般社員は社員の一覧を見られないので、
     * <strong>自分の所属を知る経路がここにしか無い</strong>（API 設計書 2.1）。
     * 返さないと、設計書が案内している経路が実際には答えを持たないことになる
     * （落とし穴 138）。
     *
     * <p><strong>労働時間制度は含めない。</strong> 含めると
     * {@code employee → workrule} という図に無い依存が生まれる。
     */
    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal AuthenticatedEmployee principal) {
        return MeResponse.of(principal, directory.find(principal.toRequester(),
                principal.employeeId(), java.util.Optional.empty()));
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

    /**
     * ログイン中の社員（API 設計書 3.1）。
     *
     * <p>{@code department} は<strong>項目ごと残す</strong>（{@code null} を出す）。
     * 省くと「所属が無い」ことを応答から読み取れなくなる（落とし穴 76）。
     * 未来日入社の社員は、基準日の時点でまだどこにも所属していない。
     */
    record MeResponse(String id, String employeeNumber, String name, String email,
                      LocalDate hiredOn, List<String> roles,
                      DepartmentResponse department) {

        /**
         * <strong>ロールは認証した利用者から取る。</strong>
         *
         * <p>{@code APPROVER} は<strong>認証時に部署長の事実から導出する</strong>ので、
         * 社員の行には入っていない。保存されているロールから作ると、
         * <strong>部署長が承認のメニューを失う。</strong>
         * 実ブラウザの通し（IT-SCN-34）が捕まえた。
         */
        static MeResponse of(AuthenticatedEmployee principal, EmployeeWithDepartment row) {
            var employee = row.employee();
            return new MeResponse(employee.id().value().toString(),
                    employee.number().value(), employee.name(),
                    employee.email().value(), employee.hiredOn(),
                    principal.roles().stream().map(Enum::name).sorted().toList(),
                    row.department().map(DepartmentResponse::from).orElse(null));
        }

        /** 所属。<strong>未来日入社の社員では {@code null} になる。</strong> */
        record DepartmentResponse(String id, String code, String name) {

            static DepartmentResponse from(
                    jp.co.sample.kintai.employee.domain.Department d) {
                return new DepartmentResponse(d.id().value().toString(),
                        d.code().value(), d.name());
            }
        }
    }
}
