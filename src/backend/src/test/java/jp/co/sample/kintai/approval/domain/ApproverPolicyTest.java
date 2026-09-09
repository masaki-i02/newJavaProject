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
 * 承認者の決定（UT-BR11-01〜18）。
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
        @DisplayName("UT-BR11-07 最上位の長の勤怠は人事が承認する")
        void escalatesToHumanResources() {
            Approver approver = policy.resolve(divisionManager, MAY, TODAY);

            assertThat(approver.kind()).isEqualTo(ApproverKind.HUMAN_RESOURCES);
            assertThat(approver.employeeId()).isEmpty();
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .containsExactly(SkipReason.SELF_APPROVAL_AVOIDED);
        }

        /**
         * <strong>廃止済みの部署は飛ばして親へ遡る</strong>（BR-11 の 4）。
         *
         * <p><strong>長を置かずに廃止する。</strong> 部署を廃止すれば部署長の在任も
         * 閉じるので、これが実際に起きる形である。しかも
         * {@code skipReasonFor} が「先に廃止を見る」ことに意味があるのは
         * <strong>まさにこの場合だけ</strong>で、長が残っていると判定を
         * 入れ替えても同じ理由が返る（順序を入れ替える変異が生き残る）。
         *
         * <p>承認者はどちらでも親の長になるので、<strong>理由まで見ないと
         * 検査にならない。</strong> 廃止された部署を「長がいないだけ」と記録すると、
         * 「なぜこの人が承認者なのか」に答えられない。
         */
        @Test
        @DisplayName("UT-BR11-05 廃止済みの部署はその部署を飛ばして親へ遡る")
        void abolishedDepartmentIsSkipped() {
            var abolished = Organization.empty();
            var target = abolished.hire("E0001", HIRED);
            var boss = abolished.hire("E0200", HIRED);
            var root = abolished.department("HQ", "本部");
            var child = abolished.department("SALES", "営業部", Optional.of(root),
                    Optional.of(LocalDate.of(2026, 4, 1)));
            abolished.assign(target, child, HIRED);
            abolished.appoint(root, boss, HIRED);

            Approver approver = new ApproverPolicy(abolished.chart())
                    .resolve(target, MAY, TODAY);

            assertThat(approver.employeeId()).contains(boss);
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .as("「長がいない」ではなく「部署が廃止されている」が本当の理由")
                    .containsExactly(SkipReason.DEPARTMENT_ABOLISHED, SkipReason.NONE);
        }

        /**
         * <strong>根まで長がいなければ人事が承認する</strong>（BR-11 の 5）。
         * 遡り切っても決まらない場合であり、{@code UT-BR11-07}（最上位の長本人）とは
         * 別の入口から同じ結論に至る。
         */
        @Test
        @DisplayName("UT-BR11-06 根まで長がいなければ人事が承認する")
        void noManagerAnywhere() {
            var headless = Organization.empty();
            var target = headless.hire("E0001", HIRED);
            var root = headless.department("HQ", "本部");
            var child = headless.department("SALES", "営業部", root);
            headless.assign(target, child, HIRED);

            Approver approver = new ApproverPolicy(headless.chart())
                    .resolve(target, MAY, TODAY);

            assertThat(approver.kind()).isEqualTo(ApproverKind.HUMAN_RESOURCES);
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .containsExactly(SkipReason.NO_MANAGER, SkipReason.NO_MANAGER);
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
        @DisplayName("UT-BR11-04 対象月のあとに退職した長はスキップされる")
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
        @DisplayName("UT-BR11-08 月中入社の初月は所属開始日を基準日にする")
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

        /**
         * <strong>基準日は入社日ではなく所属開始日である</strong>（BR-11 の 1）。
         *
         * <p>{@code UT-BR11-08} は入社日と所属開始日が同じ日なので、
         * <strong>どちらで引いても通る。</strong> 月の前から在籍している社員が
         * 月中に異動する形にして初めて、2 つが別の日になる。
         */
        @Test
        @DisplayName("UT-BR11-15 月中に異動した月は異動日が基準日になる")
        void transferredMidMonth() {
            var transferred = Organization.empty();
            var target = transferred.hire("E0001", HIRED);
            var former = transferred.hire("E0100", HIRED);
            var current = transferred.hire("E0200", HIRED);
            var sales2 = transferred.department("SALES", "営業部");
            var dev = transferred.department("DEV", "開発部");
            var transferOn = LocalDate.of(2026, 5, 15);
            transferred.assign(target, sales2, HIRED, transferOn)
                    .assign(target, dev, transferOn);
            transferred.appoint(sales2, former, HIRED);
            transferred.appoint(dev, current, HIRED);

            Approver approver = new ApproverPolicy(transferred.chart())
                    .resolve(target, MAY, TODAY);

            assertThat(approver.employeeId())
                    .as("5/1 でも入社日でも引くと異動前の営業部の長になる")
                    .contains(current);
        }

        /**
         * <strong>対象月によって承認者が変わる</strong>（BR-11 の 1）。
         * 4 月は異動前の部署、5 月は異動後の部署の長が承認する。
         *
         * <p>4 月の承認者を「今の所属」で引くと、<strong>異動した瞬間に
         * 過去の月の承認者が入れ替わる。</strong> 承認済みの月についても、
         * 「なぜこの人が承認したのか」に答えられなくなる。
         */
        @Test
        @DisplayName("UT-BR11-09 異動を挟むと、対象月によって異なる部署長が返る")
        void differentApproverPerMonth() {
            var transferred = Organization.empty();
            var target = transferred.hire("E0001", HIRED);
            var former = transferred.hire("E0100", HIRED);
            var current = transferred.hire("E0200", HIRED);
            var sales2 = transferred.department("SALES", "営業部");
            var dev = transferred.department("DEV", "開発部");
            var transferOn = LocalDate.of(2026, 5, 1);
            transferred.assign(target, sales2, HIRED, transferOn)
                    .assign(target, dev, transferOn);
            transferred.appoint(sales2, former, HIRED);
            transferred.appoint(dev, current, HIRED);
            var chart = new ApproverPolicy(transferred.chart());

            assertThat(chart.resolve(target, YearMonth.of(2026, 4), TODAY).employeeId())
                    .as("4 月は異動前の部署の長").contains(former);
            assertThat(chart.resolve(target, MAY, TODAY).employeeId())
                    .as("5 月は異動後の部署の長").contains(current);
        }

        /**
         * <strong>基準日時点の部署長が承認する</strong>（BR-11 の 3）。
         * 所属は動かず、部署長だけが交代する形。
         * {@code UT-BR11-09} とは動く側が逆で、同じ基準日の使い方を別の入口から確かめる。
         */
        @Test
        @DisplayName("UT-BR11-10 部署長が交代していれば、基準日時点の長が返る")
        void managerOfTheBasisDate() {
            var handover = Organization.empty();
            var target = handover.hire("E0001", HIRED);
            var former = handover.hire("E0100", HIRED);
            var current = handover.hire("E0200", HIRED);
            var department = handover.department("SALES", "営業部");
            var handoverOn = LocalDate.of(2026, 5, 1);
            handover.assign(target, department, HIRED);
            handover.appoint(department, former, HIRED, handoverOn);
            handover.appoint(department, current, handoverOn);
            var policyOf = new ApproverPolicy(handover.chart());

            assertThat(policyOf.resolve(target, YearMonth.of(2026, 4), TODAY).employeeId())
                    .as("4 月は交代前の長").contains(former);
            assertThat(policyOf.resolve(target, MAY, TODAY).employeeId())
                    .as("5 月は交代後の長").contains(current);
        }

        /**
         * <strong>退職日は最終在籍日</strong>（2.2）。所属は翌日で閉じるので、
         * 退職日当日はまだ所属が有効であり、承認者も導出できる。
         *
         * <p>閉区間と半開区間が混ざると、<strong>退職日当日の 1 日が消える</strong>
         * （落とし穴 10）。その日が最終月の最終勤務日であることは珍しくない。
         */
        @Test
        @DisplayName("UT-BR11-11 退職日当日も所属が有効で、承認者が導出できる")
        void retirementDay() {
            var retiring = Organization.empty();
            var retiredOn = LocalDate.of(2026, 5, 31);
            var target = retiring.hire("E0001", HIRED, Optional.of(retiredOn));
            var boss = retiring.hire("E0100", HIRED);
            var department = retiring.department("SALES", "営業部");
            // ★ 退職日当日に異動した形にして、基準日そのものを退職日にする。
            //   月初日で引くと、この境界を一度も通らない
            retiring.assign(target, department, retiredOn, retiredOn.plusDays(1));
            retiring.appoint(department, boss, HIRED);
            var chart = retiring.chart();

            assertThat(chart.departmentOf(target, retiredOn))
                    .as("退職日当日はまだ所属している").isPresent();
            assertThat(chart.departmentOf(target, retiredOn.plusDays(1)))
                    .as("翌日は所属していない").isEmpty();
            assertThat(new ApproverPolicy(chart).resolve(target, MAY, TODAY).employeeId())
                    .as("退職日当日でも承認者が導出できる").contains(boss);
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
        @DisplayName("UT-BR11-12 対象月に所属が無ければ NONE になる")
        void noAssignment() {
            var unassigned = org.hire("E0400", HIRED);

            Approver approver = policy.resolve(unassigned, MAY, TODAY);

            assertThat(approver.kind()).isEqualTo(ApproverKind.NONE);
            assertThat(approver.path()).isEmpty();
        }
    }

    @Nested
    @DisplayName("そこへ至った経路")
    class Path {

        /**
         * <strong>スキップした理由が部署ごとに順に残る。</strong>
         * 「なぜこの人が承認者なのか」は運用中に必ず問い合わせが来る。
         *
         * <p>既存の観点はどれも経路が 1〜2 段で理由も 1 種類だったので、
         * <strong>{@code path.add} を 1 か所落とす変異も、
         * 理由の判定順を入れ替える変異も、すべて生き残っていた。</strong>
         *
         * <pre>
         * 本部（長: 本部長。承認する 6/1 には退職済み）  → MANAGER_RETIRED
         *   └ 部（長: 山田本人）                        → SELF_APPROVAL_AVOIDED
         *       └ 課（長を置かない）                    → NO_MANAGER
         *           └ 山田
         * </pre>
         */
        @Test
        @DisplayName("UT-BR11-14 スキップした理由が部署ごとに順に残る")
        void everyReasonIsKeptInOrder() {
            var deep = Organization.empty();
            var target = deep.hire("E0001", HIRED);
            // 5/31 退職。対象月（5 月）には在籍しているが、承認する 6/1 にはいない
            var divisionHead = deep.hire("E0200", HIRED,
                    Optional.of(LocalDate.of(2026, 5, 31)));
            var division = deep.department("HQ", "営業本部");
            var branch = deep.department("D001", "第一営業部", division);
            var section = deep.department("S001", "第一営業課", branch);
            deep.assign(target, section, HIRED);
            deep.appoint(branch, target, HIRED);
            deep.appoint(division, divisionHead, HIRED);

            Approver approver = new ApproverPolicy(deep.chart())
                    .resolve(target, MAY, TODAY);

            assertThat(approver.kind()).isEqualTo(ApproverKind.HUMAN_RESOURCES);
            assertThat(approver.path()).extracting(ResolutionStep::reason)
                    .containsExactly(SkipReason.NO_MANAGER,
                            SkipReason.SELF_APPROVAL_AVOIDED,
                            SkipReason.MANAGER_RETIRED);
            assertThat(approver.path()).extracting(step -> step.department().name())
                    .as("下から上へ、飛ばした部署をすべて残す")
                    .containsExactly("第一営業課", "第一営業部", "営業本部");
        }
    }

    @Nested
    @DisplayName("承認してよい人か")
    class Authorization {

        @Test
        @DisplayName("UT-BR11-16 個人承認では、その本人だけが承認できる")
        void individual() {
            Approver approver = policy.resolve(yamada, MAY, TODAY);

            assertThat(approver.isApprovedBy(sectionManager, false)).isTrue();
            assertThat(approver.isApprovedBy(divisionManager, false)).isFalse();
        }

        /** BR-11 の 5。人事なら誰でもよい。特定の担当者を指名する仕組みは持たない。 */
        @Test
        @DisplayName("UT-BR11-17 人事承認では、人事ロールを持つ人なら承認できる")
        void humanResources() {
            Approver approver = policy.resolve(divisionManager, MAY, TODAY);

            assertThat(approver.isApprovedBy(yamada, true)).isTrue();
            assertThat(approver.isApprovedBy(yamada, false)).isFalse();
        }

        @Test
        @DisplayName("UT-BR11-18 承認者が決まらない月は誰も承認できない")
        void none() {
            var unassigned = org.hire("E0400", HIRED);
            Approver approver = policy.resolve(unassigned, MAY, TODAY);

            assertThat(approver.isApprovedBy(divisionManager, true)).isFalse();
        }
    }
}
