package jp.co.sample.kintai.approval.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jp.co.sample.kintai.approval.domain.ApproverPolicy;
import jp.co.sample.kintai.employee.domain.OrganizationChart;

/**
 * 承認者ポリシーの Bean 定義。
 *
 * <p>{@code ApproverPolicy} は {@code domain} に置いてある。
 * ドメインは Spring を知らない（AR-01）ので、<strong>組み立てだけをこの層が担う。</strong>
 */
@Configuration
class ApproverPolicyConfig {

    @Bean
    ApproverPolicy approverPolicy(OrganizationChart chart) {
        return new ApproverPolicy(chart);
    }
}
