package jp.co.sample.kintai.shared.presentation;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * セッションに載る認証済みの利用者。
 *
 * <p><strong>パスワードハッシュを持たない。</strong>
 * 認証が済んだ後に照合し直す経路は無く、持てばセッションに載って
 * 漏れる面積が増えるだけである（{@link #getPassword()} は空文字を返す）。
 *
 * <p>{@code application} 層へは {@link #toRequester()} で
 * {@link Requester} に変換して渡す。
 * <strong>ユースケースが Spring Security を知らない</strong>ようにするため。
 */
public record AuthenticatedEmployee(EmployeeId employeeId, String employeeNumber,
                                    String name, Set<Role> roles) implements UserDetails {

    private static final long serialVersionUID = 1L;

    public Requester toRequester() {
        return new Requester(employeeId, roles);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring Security の hasRole(...) は ROLE_ 接頭辞を前提にしている
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return employeeNumber;
    }
}
