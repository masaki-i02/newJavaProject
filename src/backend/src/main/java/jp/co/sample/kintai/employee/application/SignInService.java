package jp.co.sample.kintai.employee.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.employee.domain.Employee;
import jp.co.sample.kintai.employee.domain.EmployeeCredential;
import jp.co.sample.kintai.employee.domain.EmployeeCredentialRepository;
import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.employee.domain.PasswordAttempt;
import jp.co.sample.kintai.employee.domain.PasswordHasher;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * ログイン（要件定義書 4 章 / API 設計書 3.9）。
 *
 * <p><strong>失敗の理由を区別して返さない。</strong>
 * 「社員番号が存在しない」と「パスワードが違う」を区別すると、
 * 社員番号の総当たりで在籍者の一覧を作れる。退職済みも同じ応答にする。
 *
 * <p><strong>存在しない社員番号でも照合と同じ時間を使う。</strong>
 * 照合を飛ばすと、BCrypt の計算時間ぶんだけ応答が速くなり、
 * 応答時間の差から社員番号の存在が分かる。
 */
@Service
public class SignInService {

    private final EmployeeRepository employees;
    private final EmployeeCredentialRepository credentials;
    private final ManagershipRepository managerships;
    private final PasswordHasher hasher;
    private final Clock clock;

    public SignInService(EmployeeRepository employees,
                         EmployeeCredentialRepository credentials,
                         ManagershipRepository managerships,
                         PasswordHasher hasher, Clock clock) {
        this.employees = employees;
        this.credentials = credentials;
        this.managerships = managerships;
        this.hasher = hasher;
        this.clock = clock;
    }

    /**
     * 認証する。
     *
     * @return 認証された利用者と、<strong>そのとき有効なロール</strong>
     * @throws AuthenticationFailedException 社員番号かパスワードが違う場合。区別しない
     */
    @Transactional(readOnly = true)
    public SignedIn signIn(EmployeeNumber employeeNumber, PasswordAttempt password) {
        LocalDate today = LocalDate.now(clock);
        Optional<Employee> found = employees.findByNumber(employeeNumber)
                .filter(employee -> employee.isActiveOn(today));

        if (found.isEmpty()) {
            // ★ 見つからなくても照合と同じだけ時間を使う。応答時間で在籍を悟らせない
            hasher.wasteTime();
            throw new AuthenticationFailedException();
        }
        Employee employee = found.get();
        Optional<EmployeeCredential> credential = credentials.find(employee.id());
        if (credential.isEmpty()) {
            hasher.wasteTime();
            throw new AuthenticationFailedException();
        }
        if (!hasher.matches(password, credential.get().passwordHash())) {
            throw new AuthenticationFailedException();
        }
        return new SignedIn(employee, effectiveRolesOf(employee, today));
    }

    /**
     * その時点で有効なロール。
     *
     * <p><strong>{@code APPROVER} は永続化されたロールに含まれない。</strong>
     * 「承認者かどうか」の実体は {@code managerships}（その日に部署長を務めているか）である。
     * ロールとして別に持つと「部署長だがロールが無く 403」
     * 「ロールはあるが対象 0 件」という不整合が起きる（API 設計書 2.1）。
     *
     * <p>導出は<strong>認証の時点で 1 度だけ</strong>行う。
     * リクエストのたびに引き直すと、同じ画面の中で操作の可否が変わる。
     */
    private Set<Role> effectiveRolesOf(Employee employee, LocalDate today) {
        Set<Role> roles = EnumSet.copyOf(employee.roles());
        if (!managerships.findByManager(employee.id(), today).isEmpty()) {
            roles.add(Role.APPROVER);
        }
        return roles;
    }

    /**
     * 認証された利用者。
     *
     * @param employee 社員。氏名などを応答に載せるために持つ
     * @param roles    そのとき有効なロール
     */
    public record SignedIn(Employee employee, Set<Role> roles) {

        public Requester asRequester() {
            return new Requester(employee.id(), roles);
        }
    }

    /**
     * 認証に失敗した。
     *
     * <p><strong>理由を持たない。</strong> 持つと、いつか応答に載る。
     */
    public static final class AuthenticationFailedException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        AuthenticationFailedException() {
            super("社員番号またはパスワードが違います");
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:authentication-failed";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.UNAUTHENTICATED;
        }

        @Override
        public String title() {
            return "認証に失敗しました";
        }
    }
}
