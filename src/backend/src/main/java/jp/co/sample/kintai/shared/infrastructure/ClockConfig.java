package jp.co.sample.kintai.shared.infrastructure;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jp.co.sample.kintai.shared.domain.BusinessZone;

/**
 * 現在時刻の供給元。
 *
 * <p>{@link Clock} を DI するのは、時刻に依存するロジックをテストで固定するためである
 * （CLAUDE.md 4.3）。{@code LocalDateTime.now()} を直接呼ぶと、
 * 月末締めや深夜帯の判定を検証する手段が無くなる。
 *
 * <p>ゾーンは {@link BusinessZone#ID} に固定する。システム既定のゾーンを使うと、
 * 実行環境（CI のコンテナは UTC のことが多い）で「今日」がずれる。
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.system(BusinessZone.ID);
    }
}
