package jp.co.sample.kintai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.support.IntegrationTestBase;

/**
 * アプリケーションが起動できることを確かめる。
 *
 * <p>Spring Initializr が生成したままの形では、データソースが未設定のため
 * {@code contextLoads()} が失敗する（CLAUDE.md 落とし穴 4）。
 * 実物の PostgreSQL に接続する土台を継承する。
 */
class KintaiApplicationTests extends IntegrationTestBase {

    @Test
    @DisplayName("アプリケーションコンテキストが起動し、マイグレーションが適用される")
    void contextLoads() {
        // 起動できること自体が検証。IntegrationTestBase が Flyway の適用まで面倒を見る
    }
}
