package jp.co.sample.kintai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 認証・認可の設定（要件定義書 4 章 / API 設計書 3.9）。
 *
 * <p><strong>ここに置くのは「ロールを持っているか」までである。</strong>
 * 「配下部署の社員か」「本人か」は組織の状態に依存する業務判断なので、
 * {@code application} 層が {@code OrganizationChart} を引いて行う。
 * 認可の判断が 2 か所に散ると、片方だけを直した状態が生まれる。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    SecurityContextRepository securityContextRepository)
            throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        // SPA は XSRF-TOKEN クッキーを読んで X-XSRF-TOKEN ヘッダへ載せる。
        // ヘッダの値をそのまま使うので BREACH 対策の難読化は行わない
        csrfHandler.setCsrfRequestAttributeName(null);

        return http
                // ★ Cookie でセッションを持つので CSRF 対策を外せない。
                //   M1-a では API に認証が無かったため無効にしていた
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        // ログインの時点ではまだトークンを配っていない
                        .ignoringRequestMatchers("/api/sessions"))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(requests -> requests
                        // ログインは未認証で通す。それ以外の /api は必ず認証を要求する
                        .requestMatchers("/api/sessions").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // ★ 未認証は 401。既定のままだとログイン画面へ 302 になり、
                //   fetch から見ると「成功した HTML」が返る
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    /**
     * 認証結果の置き場所。
     *
     * <p>{@code SessionController} が明示的に保存する。
     * 既定のフォームログインを使わないので、保存も自分で行う。
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
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
