package jp.co.sample.kintai.leave.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jp.co.sample.kintai.support.PostgresSupport;

/**
 * 年次有給休暇の付与バッチが起動すること。
 *
 * <p>他のテストは {@code kintai.paid-leave.grant-scheduler.enabled: false} で
 * この Bean を作らない。固定した時計とは無関係に実時刻で走り、
 * テストの前提を黙って書き換えるからである。
 *
 * <p>その結果、<strong>cron 式が壊れていても 1 件も落ちない</strong>状態になっていた。
 * ここだけ有効にして、Bean が組み上がることと式が解釈できることを見る。
 */
@SpringBootTest(properties = "kintai.paid-leave.grant-scheduler.enabled=true")
@DisplayName("年次有給休暇の付与バッチ")
class PaidLeaveGrantSchedulerTest {

    /** 既定の実行時刻。{@code @Scheduled} の既定値と同じ文字列を置く。 */
    private static final String DEFAULT_CRON = "0 30 2 * * *";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @Autowired(required = false)
    private PaidLeaveGrantScheduler scheduler;

    @Test
    @DisplayName("IT-LV-120 付与バッチの Bean が組み上がり、実行時刻が解釈できる")
    void schedulerStarts() {
        assertThat(scheduler)
                .as("enabled=true のとき Bean が作られる")
                .isNotNull();
        assertThat(CronExpression.isValidExpression(DEFAULT_CRON)).isTrue();
    }
}
