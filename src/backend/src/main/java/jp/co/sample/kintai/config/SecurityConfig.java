package jp.co.sample.kintai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 認証・認可の設定。
 *
 * <p><strong>M1-a の時点では認証をかけていない。</strong>
 * 認証・認可は M1-c のスコープ（要件定義書 4 章・BR-11）であり、
 * ここで中途半端に入れると「誰として打刻しているか」の判定が
 * 2 か所（この設定と後で入れる本実装）に散る。
 *
 * <p>そのかわり <strong>この状態が本番へ出ないことを型と設定で担保できない</strong>ので、
 * 未決事項として残す（[CLAUDE.md 7 章] #7）。
 * M1-c では次を入れる。
 * <ul>
 *   <li>社員番号によるフォームログイン（メールは退職者の再割り当てと衝突する）</li>
 *   <li>ロールによる認可（{@code EMPLOYEE} / {@code APPROVER} / {@code HR} / {@code ADMIN}）</li>
 *   <li>閲覧範囲の判定（本人・上長・人事）。<strong>これは業務判断なので
 *       {@code application} 層に置き、ここには置かない</strong></li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // API は状態を持たないので CSRF トークンを使わない。
                // M1-c でセッション認証を入れるときに、あわせて有効化する
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /**
     * パスワードのハッシュ。
     *
     * <p>DB 側の {@code employee_credentials_hash_format_check} が
     * {@code $2[aby]$} で始まる 60 文字だけを受け付けるので、BCrypt で一致する。
     * 平文の誤保存は DB が物理的に拒否する。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
