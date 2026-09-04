package jp.co.sample.kintai.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.WorkRules;
import jp.co.sample.kintai.workrule.domain.NightWindow;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;

/**
 * 集計にまつわる通し（IT-SCN-09・10・12）。
 *
 * <p><strong>計算そのものは単体で検証済みでも、
 * 締めまで通すと止まる</strong>ことがある。
 */
@DisplayName("集計の通し")
class SettlementScenarioTest extends ScenarioTestBase {

    private static final LocalDate 入社日 = LocalDate.of(2026, 1, 1);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return 時計を(LocalDate.of(2026, 8, 10), 10, 0);
    }

    private Actor 山田;
    private Actor 課長;
    private Actor 人事;

    @BeforeEach
    void setUpOrganization() {
        山田 = 社員を登録する("E0001", "山田 太郎", 入社日, Role.EMPLOYEE);
        課長 = 社員を登録する("E0100", "課長 次郎", 入社日, Role.EMPLOYEE, Role.APPROVER);
        人事 = 社員を登録する("E0900", "人事 花子", 入社日, Role.EMPLOYEE, Role.HR);

        var 営業部 = 部署を作る("SALES", "営業部");
        所属させる(山田, 営業部, 入社日);
        所属させる(課長, 営業部, 入社日);
        部署長にする(営業部, 課長, 入社日);
    }

    /**
     * <strong>適用行を書き換えずに、改定日の前後で版が切り替わるか。</strong>
     * 改定も参照も単体では正しい。
     * <strong>改定した後に引いて初めて 0 件になる</strong>という壊れ方をする。
     * 社員は系列を指すので、版を足しても適用は切れない（ADR 0003）。
     */
    @Test
    @DisplayName("IT-SCN-09 月の途中で就業規則を改定した月を締められる")
    void closeMonthWithMidMonthRevision() throws Exception {
        var 六月 = YearMonth.of(2026, 6);
        WorkRuleSeriesId 系列 = 固定時間制を適用する(山田, 入社日);
        暦を用意する(六月);

        // 6/16 から所定を 9:00-18:00 のまま、深夜帯だけを変える改定を入れる。
        // ★ 適用行（work_rule_assignments）は触らない
        var 改定日 = LocalDate.of(2026, 6, 16);
        就業規則を改定する(山田, 系列, 改定日, WorkRules.versionOf(系列, 改定日,
                WorkRules.fixed(), Duration.ofHours(8), NightWindow.STANDARD));

        月を通して定時で働く(山田, 六月);

        // 改定日の前後どちらも計算されている
        assertThat(日次の実労働分(山田, LocalDate.of(2026, 6, 15))).isEqualTo(8 * 60);
        assertThat(日次の実労働分(山田, LocalDate.of(2026, 6, 16))).isEqualTo(8 * 60);

        // 改定日の前は旧版、後は新版が使われている
        assertThat(その日に使われた規則の版(山田, LocalDate.of(2026, 6, 15)))
                .isNotEqualTo(その日に使われた規則の版(山田, LocalDate.of(2026, 6, 16)));

        提出する(山田, 山田, 六月).andExpect(status().isOk());
        承認する(課長, 山田, 六月).andExpect(status().isOk());
        締める(人事, 山田, 六月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(山田, 六月)).isEqualTo("CLOSED");
    }

    /**
     * <strong>所定総 &gt; 総枠 の月で、時間外と不足が同時に成立するか。</strong>
     * 2026-06 は所定労働日が 22 日（176 時間）で、
     * 清算期間の総枠は 30 日 × 40 ÷ 7 ＝ 171.4 時間。
     * 所定どおり働いただけで総枠を超えるので<strong>時間外が付く。</strong>
     *
     * <p>「時間外と不足は同時に正にならない」という不変条件をここに当てると、
     * 適法な月を保存できなくなる（CLAUDE.md 落とし穴 51）。
     */
    @Test
    @DisplayName("IT-SCN-10 フレックス社員の 2026-06（所定総 > 総枠）を締められる")
    void closeFlexMonthWhereScheduledExceedsLimit() throws Exception {
        var 六月 = YearMonth.of(2026, 6);
        フレックスを適用する(山田, 入社日);
        暦を用意する(六月);

        月を通して定時で働く(山田, 六月);

        提出する(山田, 山田, 六月).andExpect(status().isOk());

        // 所定どおり働いた月に時間外が付く
        assertThat(月次の項目(山田, 六月, "working_minutes")).isEqualTo(22 * 8 * 60);
        assertThat(月次の項目(山田, 六月, "overtime_minutes"))
                .as("総枠 171.4 時間を超えたぶん").isPositive();

        承認する(課長, 山田, 六月).andExpect(status().isOk());
        締める(人事, 山田, 六月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(山田, 六月)).isEqualTo("CLOSED");
    }

    /**
     * <strong>警告は立つが、打刻も提出も承認も止まらないか。</strong>
     * 36 協定の限度時間（月 45 時間）を超えても、それは労使が是正すべき事実であり、
     * <strong>記録と手続きを止める理由にはならない。</strong>
     * 止めると、超過した月の勤怠が永久に確定しない。
     */
    @Test
    @DisplayName("IT-SCN-12 36 協定の月次上限を超えても手続きは止まらない")
    void agreementLimitDoesNotBlockTheProcess() throws Exception {
        var 七月 = YearMonth.of(2026, 7);
        固定時間制を適用する(山田, 入社日);
        暦を用意する(七月);

        // 所定労働日はすべて 9:00-21:00（1 日 4 時間の時間外）で働く。
        // 2026-07 の所定労働日は 23 日なので、時間外は 92 時間になり月 45 時間を超える
        for (LocalDate d = 七月.atDay(1); d.isBefore(七月.plusMonths(1).atDay(1));
                d = d.plusDays(1)) {
            if (所定労働日である(d)) {
                打刻する(山田, "CLOCK_IN", d.atTime(9, 0))
                        .andExpect(status().isCreated());
                打刻する(山田, "CLOCK_OUT", d.atTime(21, 0))
                        .andExpect(status().isCreated());
            }
        }

        提出する(山田, 山田, 七月).andExpect(status().isOk());

        assertThat(月次の真偽(山田, 七月, "exceeds_monthly_agreement_limit"))
                .as("限度時間の超過が警告として立つ").isTrue();

        // ★ 警告が立っていても手続きは進む
        承認する(課長, 山田, 七月).andExpect(status().isOk());
        締める(人事, 山田, 七月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(山田, 七月)).isEqualTo("CLOSED");
    }

    private String その日に使われた規則の版(Actor 社員, LocalDate 勤務日) {
        return jdbc.queryForObject("""
                SELECT work_rule_id::text FROM daily_attendances
                WHERE employee_id = ? AND work_date = ?
                """, String.class, 社員.id().value(), 勤務日);
    }
}
