package jp.co.sample.kintai.employee.application;

import java.io.Serial;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jp.co.sample.kintai.employee.domain.EmployeeCredential;
import jp.co.sample.kintai.employee.domain.EmployeeCredentialRepository;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.PasswordAttempt;
import jp.co.sample.kintai.employee.domain.PasswordHasher;
import jp.co.sample.kintai.employee.domain.RawPassword;
import jp.co.sample.kintai.shared.domain.BusinessRuleViolationException;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * パスワードの変更と初期発行（API 設計書 3.10）。
 *
 * <p>経路を 2 つに分ける。
 * <ul>
 *   <li><strong>本人が変える</strong>: 現在のパスワードを要求する</li>
 *   <li><strong>{@code ADMIN} が発行・再設定する</strong>: 現在のパスワードを要求しない</li>
 * </ul>
 *
 * <p>再設定で現在のパスワードを要求すると、
 * <strong>忘れた人の救済という目的そのものが成立しない。</strong>
 * かわりに「{@code ADMIN} しか呼べない」ことを認可で保証する。
 */
@Service
public class PasswordService {

    private final EmployeeRepository employees;
    private final EmployeeCredentialRepository credentials;
    private final PasswordHasher hasher;
    private final Clock clock;

    public PasswordService(EmployeeRepository employees,
                           EmployeeCredentialRepository credentials,
                           PasswordHasher hasher, Clock clock) {
        this.employees = employees;
        this.credentials = credentials;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** 本人が変更する。現在のパスワードが一致しなければ拒否する。 */
    @Transactional
    public void change(EmployeeId employeeId, PasswordAttempt current, RawPassword next) {
        EmployeeCredential credential = credentials.find(employeeId)
                .orElseThrow(() -> new CredentialNotFoundException(employeeId));
        if (!hasher.matches(current, credential.passwordHash())) {
            throw new CurrentPasswordMismatchException();
        }
        store(employeeId, next);
    }

    /**
     * {@code ADMIN} が初期発行・再設定する。
     *
     * <p>登録と更新で経路を分けない。「まだ発行していない」と「忘れた」は
     * 運用上の区別でしかなく、書き込む内容は同じである。
     */
    @Transactional
    public void reset(EmployeeId employeeId, RawPassword next) {
        if (employees.findById(employeeId).isEmpty()) {
            throw new CredentialNotFoundException(employeeId);
        }
        store(employeeId, next);
    }

    private void store(EmployeeId employeeId, RawPassword next) {
        credentials.save(new EmployeeCredential(employeeId, hasher.hash(next),
                LocalDateTime.now(clock)));
    }

    /** 現在のパスワードが違う。 */
    public static final class CurrentPasswordMismatchException
            extends BusinessRuleViolationException {

        @Serial
        private static final long serialVersionUID = 1L;

        CurrentPasswordMismatchException() {
            super("BR-13", "現在のパスワードが違います");
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:current-password-mismatch";
        }

        @Override
        public String title() {
            return "現在のパスワードが違います";
        }
    }

    /** 社員または認証情報が無い。 */
    public static final class CredentialNotFoundException extends DomainException {

        @Serial
        private static final long serialVersionUID = 1L;

        CredentialNotFoundException(EmployeeId employeeId) {
            super("認証情報が見つかりません: " + employeeId.value());
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
            return "認証情報が見つかりません";
        }
    }
}
