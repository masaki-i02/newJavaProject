package jp.co.sample.kintai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.ClassPathResource;

import jp.co.sample.kintai.KintaiApplication;

/**
 * 本番プロファイルの設定（IT-OPS-05〜07）。
 *
 * <p><strong>既定値が「安全な側」かは、その値を使う式ごとに確かめる</strong>
 * （CLAUDE.md 落とし穴 123）。
 * {@code application.yaml} の {@code jdbc:postgresql://localhost:5432/kintai} は
 * 開発では便利だが、本番では<strong>環境変数を渡し忘れたコンテナが
 * 黙って localhost の開発 DB を探しに行く</strong>ことを意味する。
 * 同居していれば繋がってしまう。
 *
 * <p>起動しないほうが、誤った DB へ書き込むよりはるかに安全である。
 */
@DisplayName("本番プロファイルの設定")
class ProdProfileTest {

    /**
     * <strong>接続情報の既定値を持たない。</strong>
     * 環境変数を渡さずに prod で起動すると、プレースホルダが解決できずに落ちる。
     */
    @Test
    @DisplayName("IT-OPS-05 prod は DB 接続の環境変数が無いと起動しない")
    void prodFailsWithoutDatabaseEnvironment() {
        var application = new SpringApplication(KintaiApplication.class);
        application.setAdditionalProfiles("prod");
        application.setDefaultProperties(java.util.Map.of(
                // 依存が起動しないよう Web も切る。見たいのは値の解決だけである
                "spring.main.web-application-type", "none"));

        assertThatThrownBy(() -> application.run().close())
                .as("環境変数が未設定なら起動しない")
                .isInstanceOf(Exception.class)
                .hasStackTraceContaining("'url' must start with \"jdbc\"")
                // ★ ここが本題。開発用の既定値へ黙って落ちていれば起動に成功し、
                //   そもそも例外が飛ばない
                .satisfies(thrown -> assertThat(stackTraceOf(thrown))
                        .as("開発用の既定値へ落ちていない")
                        .doesNotContain("localhost:5432"));
    }

    /**
     * <strong>設定ファイル自体に既定値を書かない。</strong>
     * 起動の検査だけだと、あとから {@code :jdbc:...} という既定値を書き足しても
     * 気づけない（そのときは起動してしまうので、上のテストは落ちるが理由が読めない）。
     * ここでは文字列として確かめる。
     */
    @Test
    @DisplayName("IT-OPS-06 application-prod.yaml に接続情報の既定値が書かれていない")
    void prodYamlHasNoFallbackDefaults() throws IOException {
        String yaml = read("application-prod.yaml");

        assertThat(yaml).contains("${KINTAI_DB_URL}");
        assertThat(yaml).contains("${KINTAI_DB_USER}");
        assertThat(yaml).contains("${KINTAI_DB_PASSWORD}");
        assertThat(yaml)
                .as("${...:既定値} の形を書かない")
                .doesNotContain("${KINTAI_DB_URL:")
                .doesNotContain("${KINTAI_DB_USER:")
                .doesNotContain("${KINTAI_DB_PASSWORD:");
    }

    /**
     * <strong>締めの途中で切られない。</strong>
     * 一括締めは 100 名ぶんを 1 トランザクションで進めるので、
     * 即座に落とされるとどこまで進んだかが分からなくなる。
     */
    @Test
    @DisplayName("IT-OPS-07 prod は graceful shutdown と Secure クッキーを設定する")
    void prodShutsDownGracefully() throws IOException {
        String yaml = read("application-prod.yaml");

        assertThat(yaml).contains("shutdown: graceful");
        assertThat(yaml)
                .as("HTTPS 前提。付けないとセッションが平文で運ばれる")
                .contains("secure: true");
        assertThat(yaml)
                .as("リバースプロキシ配下。付けないと Secure クッキーとスキーム判定が壊れる")
                .contains("forward-headers-strategy: framework");
    }

    private static String stackTraceOf(Throwable thrown) {
        var writer = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    private static String read(String name) throws IOException {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
