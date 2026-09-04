package jp.co.sample.kintai.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.shared.domain.Role;

/**
 * 在籍と組織にまつわる通し（IT-SCN-06〜08）。
 *
 * <p><strong>「実行者がいなくなる」欠陥を狙う。</strong>
 * 提出も承認も締めも個別には動くのに、
 * 月中入社・月中退職・部署長本人という条件が重なると
 * <strong>誰も先へ進められなくなる。</strong>
 * これは通しでしか分からない（結合テスト仕様書 5.1）。
 */
@DisplayName("在籍と組織の通し")
class LifecycleScenarioTest extends ScenarioTestBase {

    private static final LocalDate 入社日 = LocalDate.of(2026, 1, 1);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return 時計を(LocalDate.of(2026, 10, 10), 10, 0);
    }

    /**
     * <strong>月中入社の初月が締まるか。</strong>
     * 清算期間・所定総労働時間が在籍期間で計算されないと、
     * 日次も月次も個別には動くのに<strong>初月を締めようとして初めて止まる。</strong>
     */
    @Test
    @DisplayName("IT-SCN-06 月中入社（4/15）の初月を締められる")
    void closeFirstMonthOfMidMonthHire() throws Exception {
        var 四月 = YearMonth.of(2026, 4);
        var 入社 = LocalDate.of(2026, 4, 15);

        var 新人 = 社員を登録する("E0300", "新人 三郎", 入社, Role.EMPLOYEE);
        var 課長 = 社員を登録する("E0100", "課長 次郎", 入社日,
                Role.EMPLOYEE, Role.APPROVER);
        var 人事 = 社員を登録する("E0900", "人事 花子", 入社日, Role.EMPLOYEE, Role.HR);

        var 営業部 = 部署を作る("SALES", "営業部");
        所属させる(新人, 営業部, 入社);
        所属させる(課長, 営業部, 入社日);
        部署長にする(営業部, 課長, 入社日);

        // ★ 適用開始日は月初日ではなく入社日。月初日に限ると初月が締められない
        固定時間制を適用する(新人, 入社);
        暦を用意する(四月);

        月を通して定時で働く(新人, 入社, 四月.plusMonths(1).atDay(1));

        提出する(新人, 新人, 四月).andExpect(status().isOk());
        承認する(課長, 新人, 四月).andExpect(status().isOk());
        締める(人事, 新人, 四月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(新人, 四月)).isEqualTo("CLOSED");
        // 4/15〜4/30 の平日は 12 日。入社前の 4/1〜4/14 は数えない
        assertThat(月次の項目(新人, 四月, "working_minutes")).isEqualTo(12 * 8 * 60);
    }

    /**
     * <strong>本人がログインできなくても最終月が締まるか。</strong>
     * 9/20 退職の社員の 9 月分を提出できるのは 10 月以降であり、
     * そのとき本人はもう在籍していない。
     * 本人だけに提出を許すと<strong>提出済に到達できず、承認も締めもできない。</strong>
     */
    @Test
    @DisplayName("IT-SCN-07 月中退職（9/20）の最終月を人事が代理提出して締められる")
    void closeFinalMonthOfRetiredEmployee() throws Exception {
        var 九月 = YearMonth.of(2026, 9);
        var 退職日 = LocalDate.of(2026, 9, 20);

        var 退職者 = 社員を登録する("E0400", "退職 四郎", 入社日,
                Optional.of(退職日), Role.EMPLOYEE);
        var 課長 = 社員を登録する("E0100", "課長 次郎", 入社日,
                Role.EMPLOYEE, Role.APPROVER);
        var 人事 = 社員を登録する("E0900", "人事 花子", 入社日, Role.EMPLOYEE, Role.HR);

        var 営業部 = 部署を作る("SALES", "営業部");
        所属させる(退職者, 営業部, 入社日);
        所属させる(課長, 営業部, 入社日);
        部署長にする(営業部, 課長, 入社日);

        固定時間制を適用する(退職者, 入社日);
        暦を用意する(九月);

        月を通して定時で働く(退職者, 九月.atDay(1), 退職日.plusDays(1));

        // ★ 人事が代理で提出する。理由が証跡に残る
        代理提出する(人事, 退職者, 九月, "退職者（最終在籍日 2026-09-20）の代理提出")
                .andExpect(status().isOk());
        assertThat(月次勤怠の状態(退職者, 九月)).isEqualTo("SUBMITTED");

        承認する(課長, 退職者, 九月).andExpect(status().isOk());
        締める(人事, 退職者, 九月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(退職者, 九月)).isEqualTo("CLOSED");
        assertThat(証跡の種類(退職者, 九月))
                .as("代理提出は通常の提出と区別できる")
                .containsExactly("PROXY_SUBMIT", "APPROVE", "CLOSE");
        assertThat(jdbc.queryForObject("""
                SELECT comment FROM approval_events WHERE event_kind = 'PROXY_SUBMIT'
                """, String.class))
                .isEqualTo("退職者（最終在籍日 2026-09-20）の代理提出");
    }

    /**
     * <strong>部署長本人の勤怠を誰が承認するか。</strong>
     * 自己承認は禁じられているので、BR-11 の遡りで上位へ行く。
     * 最上位の部署長なら人事へ到達する。
     * <strong>承認者の導出は正しくても、締めまで通して初めて詰まる。</strong>
     */
    @Test
    @DisplayName("IT-SCN-08 部署長本人の勤怠を人事が承認して締められる")
    void closeMonthOfTheDepartmentManager() throws Exception {
        var 四月 = YearMonth.of(2026, 4);

        var 本部長 = 社員を登録する("E0200", "本部長 一郎", 入社日,
                Role.EMPLOYEE, Role.APPROVER);
        var 人事 = 社員を登録する("E0900", "人事 花子", 入社日, Role.EMPLOYEE, Role.HR);

        var 本部 = 部署を作る("HQ", "本部");
        所属させる(本部長, 本部, 入社日);
        部署長にする(本部, 本部長, 入社日);

        固定時間制を適用する(本部長, 入社日);
        暦を用意する(四月);
        月を通して定時で働く(本部長, 四月);

        // 承認者の照会でも人事へ到達することが分かる
        mockMvc.perform(承認者を問い合わせる(本部長, 本部長, 四月))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("HUMAN_RESOURCES"));

        提出する(本部長, 本部長, 四月).andExpect(status().isOk());

        // ★ 本人は自分を承認できない
        承認する(本部長, 本部長, 四月).andExpect(status().isForbidden());

        承認する(人事, 本部長, 四月).andExpect(status().isOk());
        締める(人事, 本部長, 四月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(本部長, 四月)).isEqualTo("CLOSED");
        assertThat(証跡の種類(本部長, 四月))
                .containsExactly("SUBMIT", "APPROVE", "CLOSE");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            承認者を問い合わせる(Actor 実行者, Actor 対象, YearMonth 月) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/employees/{id}/monthly-attendances/{month}/approver",
                        対象.id().value(), 月.toString())
                .with(as(実行者.id(), 実行者.社員番号(), 実行者.ロール()));
    }
}
