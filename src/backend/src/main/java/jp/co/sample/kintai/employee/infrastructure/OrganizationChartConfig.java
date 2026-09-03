package jp.co.sample.kintai.employee.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jp.co.sample.kintai.employee.domain.AssignmentRepository;
import jp.co.sample.kintai.employee.domain.DepartmentRepository;
import jp.co.sample.kintai.employee.domain.EmployeeRepository;
import jp.co.sample.kintai.employee.domain.ManagershipRepository;
import jp.co.sample.kintai.employee.domain.OrganizationChart;
import jp.co.sample.kintai.employee.domain.RepositoryBackedOrganizationChart;

/**
 * 組織図の Bean 定義。
 *
 * <p>{@code RepositoryBackedOrganizationChart} は {@code domain} に置いてある。
 * ドメインは Spring を知らない（AR-01）ので、<strong>組み立てだけをこの層が担う。</strong>
 */
@Configuration
class OrganizationChartConfig {

    @Bean
    OrganizationChart organizationChart(EmployeeRepository employees,
                                        DepartmentRepository departments,
                                        AssignmentRepository assignments,
                                        ManagershipRepository managerships) {
        return new RepositoryBackedOrganizationChart(
                employees, departments, assignments, managerships);
    }
}
