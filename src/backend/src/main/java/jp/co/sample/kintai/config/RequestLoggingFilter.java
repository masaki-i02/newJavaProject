package jp.co.sample.kintai.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * アクセスログと相関 ID。
 *
 * <p><strong>アクセスログが無いと、どの要求が 500 になったのかを外から追えない。</strong>
 * 例外のスタックトレースだけがログに出ていても、
 * それがどの利用者のどの操作から出たものかが分からない。
 *
 * <p>相関 ID を MDC へ入れるので、例外のログ行とアクセスログの行を
 * <strong>同じ ID で突き合わせられる</strong>。
 *
 * <p><strong>本文を書かない。</strong>
 * 打刻の時刻も年休の理由もパスワードも、要求の本文に入る。
 * 記録すべきものは監査証跡として DB の表にある
 * （アーキテクチャ設計書 6.5）。ログは障害調査のためのものである。
 *
 * <p><strong>死活監視は書かない。</strong>
 * {@code /actuator/health} は 15 秒ごとに叩かれるので、
 * 書くと 1 日 5,760 行がログの大半を占める。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger ACCESS =
            LoggerFactory.getLogger("jp.co.sample.kintai.access");

    /** 相関 ID の MDC のキー。ログの書式（`logback` の設定）から参照する。 */
    public static final String REQUEST_ID = "requestId";

    /**
     * 認証された利用者を置く要求属性のキー。
     *
     * <p><strong>{@code SecurityContextHolder} を {@code finally} で読めない。</strong>
     * このフィルタは Spring Security より外側にあるので、
     * {@code chain.doFilter} から戻ってきた時点では
     * {@code SecurityContextHolderFilter} が既に文脈を消している。
     *
     * <p>かといって内側へ移すと、Security が 401 で弾いた要求が
     * <strong>アクセスログに一切残らなくなる</strong>。
     * 外側に置いたまま、Security の内側から属性へ書いてもらう。
     */
    public static final String USER = RequestLoggingFilter.class.getName() + ".user";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            // ★ 5xx は WARN で出す。grep しなくても目に入るようにする
            if (response.getStatus() >= 500) {
                ACCESS.warn("{} {} status={} elapsedMs={} user={}",
                        request.getMethod(), path(request), response.getStatus(),
                        elapsedMillis, userOf(request));
            } else {
                ACCESS.info("{} {} status={} elapsedMs={} user={}",
                        request.getMethod(), path(request), response.getStatus(),
                        elapsedMillis, userOf(request));
            }
            MDC.remove(REQUEST_ID);
        }
    }

    /** 死活監視は記録しない。15 秒ごとに叩かれるのでログの大半を占める。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    /**
     * クエリ文字列は書かない。
     *
     * <p>{@code ?month=2026-04} 程度だが、いつか個人を特定する値が入る。
     * 書かないと決めておくほうが安い。
     */
    private static String path(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * 誰の要求か。
     *
     * <p><strong>社員番号を書く。</strong> 内部の識別子（UUID）だけだと、
     * 調べるたびに DB を引くことになる。
     * 認証されていなければ {@code anonymous}。
     */
    private static String userOf(HttpServletRequest request) {
        Object user = request.getAttribute(USER);
        return user instanceof String name ? name : "anonymous";
    }
}
