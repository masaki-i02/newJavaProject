package jp.co.sample.kintai.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 統合テストのデータソースを解決する。
 *
 * <p>既定では Testcontainers で PostgreSQL 16 を起動する。
 * <strong>H2 は使わない。</strong> {@code EXCLUDE USING gist}・レンジパーティション・
 * 配列型・生成列が再現できず、通っても意味がないため（CLAUDE.md 落とし穴 6）。
 *
 * <p>Docker が使えない環境のために、外部の PostgreSQL を指定する経路も用意する。
 * <pre>
 * ./gradlew test -Dkintai.test.datasource.url=jdbc:postgresql://localhost:5432/kintai_test
 * </pre>
 *
 * <p>いずれの場合も <strong>バージョンは 16 に固定する。</strong>
 * 設計書の DDL はすべて 16 で検証しており、検証していない版で動かす意味がない
 * （CLAUDE.md 落とし穴 21）。
 */
public final class PostgresSupport {

    /** 設計書の DDL を検証したのと同じ版。上げるときは設計書の検証もやり直す。 */
    private static final String IMAGE = "postgres:16-alpine";

    private static final String URL_PROPERTY = "kintai.test.datasource.url";
    private static final String USER_PROPERTY = "kintai.test.datasource.username";
    private static final String PASSWORD_PROPERTY = "kintai.test.datasource.password";

    /**
     * コンテナは全テストで 1 つだけ共有する。
     * 起動が最も重いので、クラスごとに立てると現実的な時間で終わらない。
     */
    private static PostgreSQLContainer<?> container;

    private PostgresSupport() {
    }

    /** 外部の PostgreSQL が指定されているか。 */
    public static boolean usesExternalDatabase() {
        return System.getProperty(URL_PROPERTY) != null;
    }

    /**
     * 1 つの Spring コンテキストが握る接続の数。
     *
     * <p><strong>既定（10）のままだとテスト全体で接続を使い切る。</strong>
     * {@code @TestBean} で時計を差し替えるたびに別のコンテキストが作られ、
     * そのそれぞれが接続プールを持つ。10 個ほど積み上がると
     * PostgreSQL の {@code max_connections}（既定 100）に届き、
     * <strong>後から起動したコンテキストが「too many clients」で丸ごと落ちる。</strong>
     *
     * <p>テストは 1 スレッドで走るので、1 コンテキストあたり 2 本あれば足りる。
     */
    private static final int TEST_POOL_SIZE = 2;

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size",
                () -> String.valueOf(TEST_POOL_SIZE));
        // 使われていない接続を抱え続けない。別のコンテキストへ早く返す
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
        registry.add("spring.datasource.hikari.idle-timeout", () -> "10000");
        if (usesExternalDatabase()) {
            registry.add("spring.datasource.url", () -> System.getProperty(URL_PROPERTY));
            registry.add("spring.datasource.username",
                    () -> System.getProperty(USER_PROPERTY, "postgres"));
            registry.add("spring.datasource.password",
                    () -> System.getProperty(PASSWORD_PROPERTY, "postgres"));
            // 外部の DB は前のテストの残骸を持っている可能性がある。
            // 実行のたびに作り直す
            registry.add("spring.flyway.clean-disabled", () -> "false");
            registry.add("spring.flyway.clean-on-validation-error", () -> "true");
            return;
        }
        PostgreSQLContainer<?> db = sharedContainer();
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    private static synchronized PostgreSQLContainer<?> sharedContainer() {
        if (container == null) {
            container = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("kintai")
                    // 打刻の制約トリガが AT TIME ZONE を使う。
                    // コンテナ側のタイムゾーンも固定する
                    .withEnv("TZ", "Asia/Tokyo");
            container.start();
        }
        return container;
    }
}
