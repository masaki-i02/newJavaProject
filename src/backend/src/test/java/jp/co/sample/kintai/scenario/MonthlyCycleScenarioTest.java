package jp.co.sample.kintai.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.shared.domain.Role;

/**
 * 1 か月の通し（IT-SCN-01〜05）。
 *
 * <p><strong>単体でも制約でも捕まらない欠陥を狙う。</strong>
 * 個々の部品は正しいのに、つないだら進めなくなる — というのが
 * このシステムの典型的な欠陥である（結合テスト仕様書 5.1）。
 *
 * <p>対象月は 2026-04。時計は 2026-05-10 に固定してあるので、
 * 「対象月の末日が到来していること」は満たされる。
 */
@DisplayName("1 か月の通し")
class MonthlyCycleScenarioTest extends ScenarioTestBase {

    private static final LocalDate 入社日 = LocalDate.of(2026, 1, 1);
    private static final YearMonth 四月 = YearMonth.of(2026, 4);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return 時計を(LocalDate.of(2026, 5, 10), 10, 0);
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

        固定時間制を適用する(山田, 入社日);
        暦を用意する(四月);
    }

    /**
     * <strong>M1 の骨格。これが通らないと何も始まらない。</strong>
     * 中間状態と証跡の両方を見る。最後だけ見ると、
     * 途中で意図しない経路を通っていても気づけない。
     */
    @Test
    @DisplayName("IT-SCN-01 打刻 → 提出 → 承認 → 締め まで通る")
    void fullMonthlyCycle() throws Exception {
        月を通して定時で働く(山田, 四月);

        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("DRAFT");

        提出する(山田, 山田, 四月).andExpect(status().isOk());
        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("SUBMITTED");

        承認する(課長, 山田, 四月).andExpect(status().isOk());
        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("APPROVED");

        締める(人事, 山田, 四月).andExpect(status().isOk());
        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("CLOSED");

        assertThat(証跡の種類(山田, 四月))
                .containsExactly("SUBMIT", "APPROVE", "CLOSE");

        // 4 月の所定労働日は 22 日。定時どおりなので時間外は付かない
        assertThat(月次の項目(山田, 四月, "working_minutes")).isEqualTo(22 * 8 * 60);
        assertThat(月次の項目(山田, 四月, "daily_overtime_minutes")).isZero();
    }

    /**
     * <strong>勤務日と暦日の区別が、集計から締めまで一貫しているか。</strong>
     * 土曜 22:00 出勤 → 日曜 06:00 退勤は、勤務日としては土曜 1 日である。
     * 暦日で振り分けると、出勤の無い日曜に退勤だけが残る（BR-03）。
     */
    @Test
    @DisplayName("IT-SCN-02 日をまたぐ勤務を含む月を締められる")
    void monthWithOvernightShift() throws Exception {
        月を通して定時で働く(山田, 四月);

        var 土曜 = LocalDate.of(2026, 4, 11);
        打刻する(山田, "CLOCK_IN", 土曜.atTime(22, 0)).andExpect(status().isCreated());
        打刻する(山田, "CLOCK_OUT", 土曜.plusDays(1).atTime(6, 0))
                .andExpect(status().isCreated())
                // ★ 退勤は「出勤した日の勤務」に付く。翌日の日曜ではない
                .andExpect(jsonPath("$.workDate").value(土曜.toString()));

        assertThat(日次の実労働分(山田, 土曜)).isEqualTo(8 * 60);
        assertThat(日次の実労働分(山田, 土曜.plusDays(1)))
                .as("日曜には勤務が作られない").isNull();

        提出する(山田, 山田, 四月).andExpect(status().isOk());
        承認する(課長, 山田, 四月).andExpect(status().isOk());
        締める(人事, 山田, 四月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("CLOSED");
    }

    /**
     * <strong>訂正の承認が、日次と月次の両方を再計算し、月次勤怠を下書きへ戻すか。</strong>
     * どれか 1 つでも取り残されると、直したはずの数字が別の場所に古いまま残る。
     */
    @Test
    @DisplayName("IT-SCN-03 打刻漏れ → 訂正申請 → 承認 → 再提出 → 締め")
    void correctMissingPunchThenClose() throws Exception {
        月を通して定時で働く(山田, 四月);

        // 4/6 は休憩の打刻を忘れていた
        var 対象日 = LocalDate.of(2026, 4, 6);
        assertThat(日次の実労働分(山田, 対象日)).isEqualTo(8 * 60);

        提出する(山田, 山田, 四月).andExpect(status().isOk());

        String 申請 = 訂正を申請する(山田, 対象日, "休憩の打刻を忘れました",
                打刻を足す("BREAK_START", 対象日.atTime(12, 0)) + ","
                        + 打刻を足す("BREAK_END", 対象日.atTime(13, 0)));

        訂正を承認する(課長, 申請).andExpect(status().isOk())
                // ★ 提出済だった月が下書きに戻ることを、承認者と申請者に伝える
                .andExpect(jsonPath("$.monthlyAttendanceStatus").value("DRAFT"));

        // 休憩 1 時間ぶん減る
        assertThat(日次の実労働分(山田, 対象日)).isEqualTo(7 * 60);
        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("DRAFT");
        assertThat(月次の項目(山田, 四月, "working_minutes"))
                .as("月次も計算し直される").isEqualTo(22 * 8 * 60 - 60);

        // 再提出して締める
        提出する(山田, 山田, 四月).andExpect(status().isOk());
        承認する(課長, 山田, 四月).andExpect(status().isOk());
        締める(人事, 山田, 四月).andExpect(status().isOk());

        assertThat(証跡の種類(山田, 四月)).containsExactly(
                "SUBMIT", "REVERT_BY_CORRECTION", "SUBMIT", "APPROVE", "CLOSE");
    }

    /**
     * <strong>提出済では打刻が拒否され、訂正申請は受け付けられるか。</strong>
     * 1 つにまとめると提出済で打刻が通り、
     * 承認者が見た内容と確定する内容が食い違う。
     */
    @Test
    @DisplayName("IT-SCN-04 提出後は打刻できないが、訂正は申請できる")
    void punchAndCorrectionAfterSubmission() throws Exception {
        月を通して定時で働く(山田, 四月);
        提出する(山田, 山田, 四月).andExpect(status().isOk());

        var 未打刻の日 = LocalDate.of(2026, 4, 11);
        打刻する(山田, "CLOCK_IN", 未打刻の日.atTime(9, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:kintai:error:month-not-open"));

        // 訂正申請は受け付ける
        訂正を申請する(山田, LocalDate.of(2026, 4, 6), "休憩の打刻を忘れました",
                打刻を足す("BREAK_START", LocalDate.of(2026, 4, 6).atTime(12, 0)) + ","
                        + 打刻を足す("BREAK_END", LocalDate.of(2026, 4, 6).atTime(13, 0)));

        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("SUBMITTED");
    }

    /**
     * <strong>差戻しの理由が証跡に残り、下書きが打刻を再び受け付けるか。</strong>
     * 差し戻しても打刻できないままだと、本人は指摘に対応できない。
     */
    @Test
    @DisplayName("IT-SCN-05 差戻し → 修正 → 再提出 → 承認")
    void rejectThenResubmit() throws Exception {
        月を通して定時で働く(山田, 四月);
        提出する(山田, 山田, 四月).andExpect(status().isOk());

        差し戻す(課長, 山田, 四月, "4/11 の休日出勤が入っていません")
                .andExpect(status().isOk());
        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("DRAFT");

        assertThat(jdbc.queryForObject("""
                SELECT comment FROM approval_events WHERE event_kind = 'REJECT'
                """, String.class))
                .as("差戻しの理由が証跡に残る")
                .isEqualTo("4/11 の休日出勤が入っていません");

        // 下書きに戻ったので打刻できる
        var 土曜 = LocalDate.of(2026, 4, 11);
        定時で働く(山田, 土曜);
        assertThat(日次の実労働分(山田, 土曜)).isEqualTo(8 * 60);

        提出する(山田, 山田, 四月).andExpect(status().isOk());
        承認する(課長, 山田, 四月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("APPROVED");
        assertThat(証跡の種類(山田, 四月))
                .containsExactly("SUBMIT", "REJECT", "SUBMIT", "APPROVE");
    }
}
