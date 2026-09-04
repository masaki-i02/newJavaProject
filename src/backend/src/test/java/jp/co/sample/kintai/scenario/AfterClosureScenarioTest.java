package jp.co.sample.kintai.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.workrule.domain.WorkRuleSeriesId;

/**
 * 締めたあとに変えようとする（IT-SCN-11）。
 *
 * <p><strong>4 つの経路すべてを試す。</strong>
 * 打刻・訂正申請・カレンダーの変更・就業規則の遡及適用。
 * どれか 1 つでも通ると、確定した勤怠と矛盾する値が生まれ、
 * <strong>締めを戻す手段が無いので矛盾したまま残る。</strong>
 *
 * <p>1 つの経路だけを塞いで満足しないために、通しで 4 つとも見る。
 */
@DisplayName("締めたあとの変更")
class AfterClosureScenarioTest extends ScenarioTestBase {

    private static final LocalDate 入社日 = LocalDate.of(2026, 1, 1);
    private static final YearMonth 四月 = YearMonth.of(2026, 4);
    private static final LocalDate 対象日 = LocalDate.of(2026, 4, 6);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return 時計を(LocalDate.of(2026, 5, 10), 10, 0);
    }

    private Actor 山田;
    private Actor 課長;
    private Actor 人事;
    private WorkRuleSeriesId 系列;

    @BeforeEach
    void setUpAndClose() throws Exception {
        山田 = 社員を登録する("E0001", "山田 太郎", 入社日, Role.EMPLOYEE);
        課長 = 社員を登録する("E0100", "課長 次郎", 入社日, Role.EMPLOYEE, Role.APPROVER);
        人事 = 社員を登録する("E0900", "人事 花子", 入社日, Role.EMPLOYEE, Role.HR);

        var 営業部 = 部署を作る("SALES", "営業部");
        所属させる(山田, 営業部, 入社日);
        所属させる(課長, 営業部, 入社日);
        部署長にする(営業部, 課長, 入社日);

        系列 = 固定時間制を適用する(山田, 入社日);
        暦を用意する(四月);

        月を通して定時で働く(山田, 四月);
        提出する(山田, 山田, 四月).andExpect(status().isOk());
        承認する(課長, 山田, 四月).andExpect(status().isOk());
        締める(人事, 山田, 四月).andExpect(status().isOk());

        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("IT-SCN-11 締め後は打刻・訂正申請・カレンダー変更・規則の遡及適用がすべて拒否される")
    void everyPathIsRejectedAfterClosure() throws Exception {
        // 1. 打刻
        打刻する(山田, "CLOCK_IN", LocalDate.of(2026, 4, 11).atTime(9, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:kintai:error:month-not-open"));

        // 2. 訂正申請
        mockMvc.perform(post("/api/employees/{id}/correction-requests", 山田.id().value())
                        .with(as(山田.id(), 山田.社員番号(), 山田.ロール()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workDate":"%s","reason":"休憩を忘れました",
                                 "items":[{"action":"ADD","eventType":"BREAK_START",
                                           "occurredAt":"%sT12:00:00"}]}
                                """.formatted(対象日, 対象日)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:kintai:error:month-already-closed"));

        // 3. カレンダーの変更
        //    ★ 暦日区分が変わると休日割増の計算が変わり、確定済みの勤怠と矛盾する
        mockMvc.perform(put("/api/calendars/{date}", 対象日)
                        .with(as(人事.id(), 人事.社員番号(), 人事.ロール()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"LEGAL_HOLIDAY\",\"name\":\"あとから休日\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:kintai:error:month-already-closed"));

        // 4. 就業規則の遡及適用
        //    ★ 所定が変わると、確定済みの月の時間外・不足が後から変わる
        mockMvc.perform(post("/api/employees/{id}/work-rule-assignments",
                        山田.id().value())
                        .with(as(人事.id(), 人事.社員番号(), 人事.ロール()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seriesId\":\"%s\",\"validFrom\":\"%s\"}"
                                .formatted(系列.value(), 四月.atDay(1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:kintai:error:month-already-closed"));

        // 確定した値は 1 つも動いていない
        assertThat(月次勤怠の状態(山田, 四月)).isEqualTo("CLOSED");
        assertThat(日次の実労働分(山田, 対象日)).isEqualTo(8 * 60);
        assertThat(月次の項目(山田, 四月, "working_minutes")).isEqualTo(22 * 8 * 60);
    }

    /**
     * <strong>締めていない月なら、同じ変更が通る。</strong>
     * すべてを一律に拒む実装でも IT-SCN-11 は通ってしまうので、
     * 拒否が締めを理由にしていることをここで確かめる。
     */
    @Test
    @DisplayName("IT-SCN-26 締めていない月であれば、同じ変更が通る")
    void sameChangesSucceedForAnOpenMonth() throws Exception {
        var 五月 = LocalDate.of(2026, 5, 1);

        mockMvc.perform(put("/api/calendars/{date}", 五月)
                        .with(as(人事.id(), 人事.社員番号(), 人事.ロール()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"LEGAL_HOLIDAY\",\"name\":\"創立記念日\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/employees/{id}/work-rule-assignments",
                        山田.id().value())
                        .with(as(人事.id(), 人事.社員番号(), 人事.ロール()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seriesId\":\"%s\",\"validFrom\":\"%s\"}"
                                .formatted(系列.value(), 五月)))
                .andExpect(status().isNoContent());
    }

    /** カレンダーの変更は人事だけ。 */
    @Test
    @DisplayName("IT-SCN-27 人事でなければカレンダーを変更できない")
    void onlyHumanResourcesCanChangeTheCalendar() throws Exception {
        mockMvc.perform(put("/api/calendars/{date}", LocalDate.of(2026, 5, 1))
                        .with(as(山田.id(), 山田.社員番号(), 山田.ロール()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"LEGAL_HOLIDAY\",\"name\":\"勝手に休日\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * <strong>他人が締めた月のカレンダーも変更できない。</strong>
     * カレンダーは全社で共有する 1 つの表なので、
     * 社員ごとの締め判定では足りない。
     */
    @Test
    @DisplayName("IT-SCN-28 別の社員が未締めでも、誰かが締めた月は変更できない")
    void anyoneClosedBlocksTheCalendar() throws Exception {
        // 課長は 4 月を締めていない
        assertThat(月次勤怠の状態(課長, 四月)).isEqualTo("DRAFT");

        mockMvc.perform(put("/api/calendars/{date}", 対象日)
                        .with(as(人事.id(), 人事.社員番号(), 人事.ロール()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"LEGAL_HOLIDAY\",\"name\":\"あとから休日\"}"))
                .andExpect(status().isConflict());
    }
}
