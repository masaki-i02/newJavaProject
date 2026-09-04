package jp.co.sample.kintai.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.employee.domain.DepartmentId;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.support.Organization;

/**
 * 承認者の決定（UT-BR11-01〜10）。
 *
 * <p>BR-11 は「自部署の長 → 見つからなければ上位へ → 最後は人事」という遡り方を定める。
 * <strong>途中でスキップした理由を残す</strong>ことが、この設計の要点である。
 * 「なぜこの人が承認者なのか」は運用中に必ず問い合わせが来る。
 *
 * <pre>
 * 本部（HQ）           長: 部長
 *   └ 営業部（SALES）  長: 課長
 *       └ 山田
 * </pre>
 */
@DisplayName("承認者の決定（BR-11）")
class ApproverPolicyTest {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);
    private static final YearMonth MAY = YearMonth.of(2026, 5);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private final Organization org = Organization.empty();

    private EmployeeId yamada;
    private EmployeeId sectionManager;
    private EmployeeId divisionManager;
    private DepartmentId hq;
    private DepartmentId sales;
    private ApproverPolicy policy;

    @BeforeEach
    void setUpOrganization() {
        yamada = org.hire("E0001", HIRED);
        sectionManager = org.hire("E0100", HIRED);
        divisionManager = org.hire("E0200", HIRED);

        hq = org.department("HQ", "本部");
        sales = org.department("SALES", "営業部", hq);

        org.assign(yamada, sales, HIRED);
        org.assign(sectionManager, sales, HIRED);
        org.assign(divisionManager, hq, HIRED);
        org.appoint(sales, sectionManager, HIRED);
        org.appoint(hq, divisionManager, HIRED);

        policy = new ApproverPolicy(org.chart());
    }

    @Nested
    @DisplayName("基本の遡り")
    class Basic {

        @Test
        @DisplayName("UT-BR11-01 自部署の長が承認者になる")
        void ownDepartmentManager() {
            Approver approver = policy.resolve(yamada, MAY, TODAY);

            assertThat(approver.kind()).isEqualTo(ApproverKind.INDIVIDUAL);
            assertThat(approver.employeeId()).contains(sectionManager);
            assertThat(approver.path()).hasSize(1);
            assertThat(approver.path().getFirst().reason()).isEqualTo(SkipReason.NONE);
        }

        @Test
        @DisplayName("UT-BR11-02 自部署に長がいなければ上位の長へ遡る")
        void escalatesToParent() {
            var noManager = Organization.empty();
            var target = noManager.hire("E0001", HIRED);
            var boss = noManager.hire("E0200", HIRED);
            var root = noManager.department("HQ", "本部");
            var child = noManager.department("SALES", "営業部", root);
            noManager.assign(target, child, HIRED);
            noManager.appoint(root, boss, HIRED);

            Approver approver = new ApproverPolicy(noManager.chart())
                    .resolve(target, MAY, TODAY);

            assertThat(approver.employeeId()).contains(boss);
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .containsExactly(SkipReason.NO_MANAGER, SkipReason.NONE);
        }

        /**
         * <strong>自分は自分を承認できない</strong>（BR-11 の 4）。
         * 部署長本人の勤怠は、さらに上へ遡って承認者を探す。
         * これを見落とすと、部署長の勤怠が永久に締められない。
         */
        @Test
        @DisplayName("UT-BR11-03 部署長本人の勤怠は上位の長へ遡る")
        void selfApprovalIsAvoided() {
            Approver approver = policy.resolve(sectionManager, MAY, TODAY);

            assertThat(approver.employeeId()).contains(divisionManager);
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .containsExactly(SkipReason.SELF_APPROVAL_AVOIDED, SkipReason.NONE);
        }

        /**
         * <strong>遡っても得られなければ人事が承認する</strong>（BR-11 の 5）。
         * 最上位の部署長本人の勤怠がこれにあたる。
         */
        @Test
        @DisplayName("UT-BR11-04 最上位の長の勤怠は人事が承認する")
        void escalatesToHumanResources() {
            Approver approver = policy.resolve(divisionManager, MAY, TODAY);

            assertThat(approver.kind()).isEqualTo(ApproverKind.HUMAN_RESOURCES);
            assertThat(approver.employeeId()).isEmpty();
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .containsExactly(SkipReason.SELF_APPROVAL_AVOIDED);
        }
    }

    @Nested
    @DisplayName("2 つの日付")
    class TwoDates {

        /**
         * <strong>在籍判定だけ「今日」を使う。</strong>
         * 基準日で見ると、対象月のあとに退職した部署長が承認者として返り、
         * <strong>誰も承認できなくなる。</strong>
         */
        @Test
        @DisplayName("UT-BR11-05 対象月のあとに退職した長はスキップされる")
        void retiredManagerIsSkipped() {
            var retiring = Organization.empty();
            var target = retiring.hire("E0001", HIRED);
            // 5/31 退職。対象月（5 月）には在籍しているが、承認する 6/1 にはいない
            var manager = retiring.hire("E0100", HIRED,
                    Optional.of(LocalDate.of(2026, 5, 31)));
            var boss = retiring.hire("E0200", HIRED);
            var root = retiring.department("HQ", "本部");
            var child = retiring.department("SALES", "営業部", root);
            retiring.assign(target, child, HIRED);
            retiring.appoint(child, manager, HIRED);
            retiring.appoint(root, boss, HIRED);

            Approver approver = new ApproverPolicy(retiring.chart())
                    .resolve(target, MAY, TODAY);

            assertThat(approver.employeeId()).contains(boss);
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .containsExactly(SkipReason.MANAGER_RETIRED, SkipReason.NONE);
        }

        /**
         * <strong>月中入社の初月は入社日が基準日になる</strong>（BR-11 の 1）。
         * 月初日で引くと所属が無く、承認者が導出できないまま
         * <strong>勤怠を永久に締められない。</strong>
         */
        @Test
        @DisplayName("UT-BR11-06 月中入社の初月は入社日を基準日にする")
        void midMonthHire() {
            var midMonth = Organization.empty();
            var newcomer = midMonth.hire("E0300", LocalDate.of(2026, 5, 15));
            var boss = midMonth.hire("E0200", HIRED);
            var department = midMonth.department("SALES", "営業部");
            midMonth.assign(newcomer, department, LocalDate.of(2026, 5, 15));
            midMonth.appoint(department, boss, HIRED);

            Approver approver = new ApproverPolicy(midMonth.chart())
                    .resolve(newcomer, MAY, TODAY);

            assertThat(approver.employeeId())
                    .as("5/1 で引くと所属が無く、承認者が決まらない").contains(boss);
        }
    }

    @Nested
    @DisplayName("承認者を問えない場合")
    class NoApprover {

        /**
         * 対象月にまったく所属が無い（入社前・退職後）。
         * <strong>その月には月次勤怠が存在しないので、提出も承認も起きない。</strong>
         * BR-11 の 5 に反しているわけではない。
         */
        @Test
        @DisplayName("UT-BR11-07 対象月に所属が無ければ NONE になる")
        void noAssignment() {
            var unassigned = org.hire("E0400", HIRED);

            Approver approver = policy.resolve(unassigned, MAY, TODAY);

            assertThat(approver.kind()).isEqualTo(ApproverKind.NONE);
            assertThat(approver.path()).isEmpty();
        }
    }

    @Nested
    @DisplayName("承認してよい人か")
    class Authorization {

        @Test
        @DisplayName("UT-BR11-08 個人承認では、その本人だけが承認できる")
        void individual() {
            Approver approver = policy.resolve(yamada, MAY, TODAY);

            assertThat(approver.isApprovedBy(sectionManager, false)).isTrue();
            assertThat(approver.isApprovedBy(divisionManager, false)).isFalse();
        }

        /** BR-11 の 5。人事なら誰でもよい。特定の担当者を指名する仕組みは持たない。 */
        @Test
        @DisplayName("UT-BR11-09 人事承認では、人事ロールを持つ人なら承認できる")
        void humanResources() {
            Approver approver = policy.resolve(divisionManager, MAY, TODAY);

            assertThat(approver.isApprovedBy(yamada, true)).isTrue();
            assertThat(approver.isApprovedBy(yamada, false)).isFalse();
        }

        @Test
        @DisplayName("UT-BR11-10 承認者が決まらない月は誰も承認できない")
        void none() {
            var unassigned = org.hire("E0400", HIRED);
            Approver approver = policy.resolve(unassigned, MAY, TODAY);

            assertThat(approver.isApprovedBy(divisionManager, true)).isFalse();
        }
    }
}
