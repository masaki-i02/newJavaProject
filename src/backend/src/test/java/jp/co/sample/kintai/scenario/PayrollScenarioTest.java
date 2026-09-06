package jp.co.sample.kintai.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;

import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.shared.domain.BusinessZone;
import jp.co.sample.kintai.shared.domain.Role;

/**
 * 給与連携までの通し（IT-SCN-29〜31・BR-18）。
 *
 * <p><strong>月次の業務を最初から最後まで 1 本で通す。</strong>
 * 打刻 → 提出 → 承認 → 締め → 給与へ渡す、が 1 か月の業務であり、
 * 出力だけを切り出したテストでは「締めた月の値がそのまま出るか」を検査しない。
 *
 * <p>HTTP を通す。アプリケーションサービスを直接呼ぶと 1 シナリオが
 * 1 トランザクションになり、リクエストの境界で効く検査を再現できない。
 */
@DisplayName("給与連携までの通し（BR-18）")
class PayrollScenarioTest extends ScenarioTestBase {

    /** 5 月分を 6/10 に出力する。対象月が終わっている必要がある（BR-10）。 */
    private static final LocalDate 今日 = LocalDate.of(2026, 6, 10);
    private static final YearMonth 五月 = YearMonth.of(2026, 5);
    private static final LocalDate 入社日 = LocalDate.of(2020, 4, 1);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(今日.atTime(10, 0).atZone(BusinessZone.ID).toInstant(),
                BusinessZone.ID);
    }

    private Actor 太郎;
    private Actor 課長;
    private Actor 人事;

    @BeforeEach
    void setUpBusinessContext() {
        太郎 = 社員を登録する("E0001", "山田 太郎", 入社日, Role.EMPLOYEE);
        課長 = 社員を登録する("E0500", "佐藤 課長", 入社日, Role.EMPLOYEE, Role.APPROVER);
        人事 = 社員を登録する("E0900", "人事 花子", 入社日, Role.EMPLOYEE, Role.HR);

        DepartmentId 営業部 = 部署を作る("SALES", "営業部");
        所属させる(太郎, 営業部, 入社日);
        所属させる(課長, 営業部, 入社日);
        所属させる(人事, 営業部, 入社日);
        部署長にする(営業部, 課長, 入社日);

        // ★ 3 人とも同じ就業規則。年度の所定が 1 つに定まらないと分母を返せない
        var 標準勤務 = 固定時間制を適用する(太郎, 入社日);
        就業規則系列リポジトリ.assign(課長.id(), 標準勤務, 入社日);
        就業規則系列リポジトリ.assign(人事.id(), 標準勤務, 入社日);

        // ★ 年度の全日を登録する。給与の分母（労基則 19 条 1 項 4 号）は年度全体から決まる
        暦を用意する(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1));
    }

    /**
     * <strong>1 か月の業務を通す。</strong>
     * 打刻した値が、締めを経て CSV の 1 行になるところまでを確かめる。
     */
    @Test
    @DisplayName("IT-SCN-29 打刻から給与連携まで通す")
    void punchToPayroll() throws Exception {
        月を通して定時で働く(太郎, 五月);
        提出する(太郎, 太郎, 五月).andExpect(status().isOk());
        承認する(課長, 太郎, 五月).andExpect(status().isOk());
        締める(人事, 太郎, 五月).andExpect(status().isOk());

        String 出力 = 給与連携を作る();

        String csv = mockMvc.perform(認証つき(
                        get("/api/payroll/exports/{id}", 出力), 人事))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv.lines().count()).as("ヘッダ行 + 締めた 1 名").isEqualTo(2);
        String[] 列 = csv.lines().skip(1).findFirst().orElseThrow().split(",");
        assertThat(列[0]).isEqualTo("E0001");
        assertThat(列[3]).as("清算期間の終了日は閉区間の最終日").isEqualTo("2026-05-31");
        assertThat(Integer.parseInt(列[9]))
                .as("所定労働日 21 日 × 8 時間").isEqualTo(21 * 480);
        assertThat(Integer.parseInt(列[10]) + Integer.parseInt(列[11]))
                .as("所定内 + 所定超 = 実労働").isEqualTo(Integer.parseInt(列[9]));
        assertThat(Integer.parseInt(列[17])).as("不足時間は無い").isZero();
    }

    /**
     * <strong>年度の分母を使った出力のあと、その年度のカレンダーは変えられない。</strong>
     *
     * <p>締め済みの月は既に守られているが、分母は<strong>年度全体</strong>から決まる。
     * 4 月分を払ったあとに 12 月（未締め）の休日を増やすと、
     * 既に払った割増賃金の単価が事後的に足りなくなる（労基法 37 条の割増は下限）。
     * 誰も気づけないまま起こるので、変更の側で止める。
     */
    @Test
    @DisplayName("IT-SCN-30 出力済みの年度のカレンダーは変えられない")
    void exportedFiscalYearIsProtected() throws Exception {
        月を通して定時で働く(太郎, 五月);
        提出する(太郎, 太郎, 五月).andExpect(status().isOk());
        承認する(課長, 太郎, 五月).andExpect(status().isOk());
        締める(人事, 太郎, 五月).andExpect(status().isOk());
        給与連携を作る();

        // 12 月はまだ締めていないが、年度の分母が動くので拒否される
        mockMvc.perform(認証つき(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/calendars/{date}", "2026-12-30")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"dayType\":\"NON_LEGAL_HOLIDAY\",\"name\":\"年末休暇\"}"),
                        人事))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:kintai:error:fiscal-year-used-by-payroll"));
    }

    /**
     * <strong>締めていない社員がいても、締めた社員の給与は出る。</strong>
     * 全件を止めると 1 人の未締めで給与処理が止まる（BR-10 の一括締めと同じ判断）。
     * 除外した社員は理由つきで返し、人事が次に何をすればよいか分かるようにする。
     */
    @Test
    @DisplayName("IT-SCN-31 未締めの社員がいても締めた社員は出力される")
    void notClosedDoesNotStopOthers() throws Exception {
        月を通して定時で働く(太郎, 五月);
        提出する(太郎, 太郎, 五月).andExpect(status().isOk());
        承認する(課長, 太郎, 五月).andExpect(status().isOk());
        締める(人事, 太郎, 五月).andExpect(status().isOk());

        // 課長は働いたが提出していない
        月を通して定時で働く(課長, 五月);

        mockMvc.perform(認証つき(post("/api/payroll/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":\"2026-05\"}"), 人事))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0500')].reason")
                        .value("NOT_SUBMITTED"))
                .andExpect(jsonPath("$.excluded[?(@.employeeNumber=='E0900')].reason")
                        .value("NO_ATTENDANCE_RECORD"));
    }

    private String 給与連携を作る() throws Exception {
        String body = mockMvc.perform(認証つき(post("/api/payroll/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":\"2026-05\"}"), 人事))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("exportId").asString();
    }
}
