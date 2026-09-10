package jp.co.sample.kintai.employee.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.EmployeeVisibility;
import jp.co.sample.kintai.shared.domain.Requester;
import jp.co.sample.kintai.shared.domain.Role;
import jp.co.sample.kintai.support.Organization;

/**
 * 閲覧範囲（UT-AUTH-01〜09 / 要件定義書 4.1）。
 *
 * <p><strong>ロールだけでは決まらない。</strong>
 * {@code APPROVER} の範囲は「自分が長を務める部署の配下」であり、
 * 組織の状態と基準日に依存する。だから Spring Security の認可設定には置けない。
 *
 * <pre>
 * 本部（HQ）           長: 部長
 *   ├ 営業部（SALES）  長: 課長
 *   │   └ 山田
 *   └ 総務部（ADMIN）
 *       └ 鈴木
 * </pre>
 */
@DisplayName("閲覧範囲（要件定義書 4.1）")
class EmployeeVisibilityTest {

    private static final LocalDate HIRED = LocalDate.of(2026, 1, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 1);

    private final Organization org = Organization.empty();

    private EmployeeId yamada;
    private EmployeeId suzuki;
    private EmployeeId sectionManager;
    private EmployeeId divisionManager;
    private EmployeeVisibility visibility;

    @BeforeEach
    void setUpOrganization() {
        yamada = org.hire("E0001", HIRED);
        suzuki = org.hire("E0002", HIRED);
        sectionManager = org.hire("E0100", HIRED);
        divisionManager = org.hire("E0200", HIRED);

        var hq = org.department("HQ", "本部");
        var sales = org.department("SALES", "営業部", hq);
        var general = org.department("ADMIN", "総務部", hq);

        org.assign(yamada, sales, HIRED);
        org.assign(suzuki, general, HIRED);
        org.assign(sectionManager, sales, HIRED);
        org.assign(divisionManager, hq, HIRED);
        org.appoint(sales, sectionManager, HIRED);
        org.appoint(hq, divisionManager, HIRED);

        visibility = new OrganizationBackedEmployeeVisibility(org.approverScope());
    }

    private Requester requester(EmployeeId id, Role... roles) {
        return new Requester(id, Set.of(roles));
    }

    @Nested
    @DisplayName("本人と全社")
    class SelfAndEveryone {

        @Test
        @DisplayName("UT-AUTH-01 本人はいつでも見られる")
        void self() {
            assertThat(visibility.canView(requester(yamada, Role.EMPLOYEE), yamada, TODAY))
                    .isTrue();
        }

        @Test
        @DisplayName("UT-AUTH-02 一般社員は他人を見られない")
        void otherEmployee() {
            assertThat(visibility.canView(requester(yamada, Role.EMPLOYEE), suzuki, TODAY))
                    .isFalse();
        }

        @Test
        @DisplayName("UT-AUTH-03 人事とシステム管理者は全社員を見られる")
        void hrAndAdmin() {
            assertThat(visibility.canView(requester(yamada, Role.EMPLOYEE, Role.HR),
                    suzuki, TODAY)).isTrue();
            assertThat(visibility.canView(requester(yamada, Role.EMPLOYEE, Role.ADMIN),
                    suzuki, TODAY)).isTrue();
        }
    }

    @Nested
    @DisplayName("承認者の範囲")
    class ApproverScope {

        @Test
        @DisplayName("UT-AUTH-04 部署長は自分の部署の社員を見られる")
        void ownDepartment() {
            assertThat(visibility.canView(
                    requester(sectionManager, Role.EMPLOYEE, Role.APPROVER), yamada, TODAY))
                    .isTrue();
        }

        /**
         * <strong>上位の部署長は配下すべてを見られる。</strong>
         * 判定は対象の所属から<strong>根へ向かって</strong>辿る。
         * 部署ツリーを下向きに展開すると、階層が深いほど広い範囲を舐めることになる。
         */
        @Test
        @DisplayName("UT-AUTH-05 上位部署の長は配下の部署の社員も見られる")
        void descendantDepartments() {
            assertThat(visibility.canView(
                    requester(divisionManager, Role.EMPLOYEE, Role.APPROVER), yamada, TODAY))
                    .isTrue();
            assertThat(visibility.canView(
                    requester(divisionManager, Role.EMPLOYEE, Role.APPROVER), suzuki, TODAY))
                    .isTrue();
        }

        /**
         * <strong>横は見られない。</strong>
         * 営業部の長は総務部の社員を見られない。
         * 「承認者ロールを持っているか」だけで判定すると、ここが通ってしまう。
         */
        @Test
        @DisplayName("UT-AUTH-06 別系統の部署の社員は見られない")
        void siblingDepartment() {
            assertThat(visibility.canView(
                    requester(sectionManager, Role.EMPLOYEE, Role.APPROVER), suzuki, TODAY))
                    .isFalse();
        }

        /**
         * <strong>ロールだけあっても、部署長でなければ何も見られない。</strong>
         * 実装上 {@code APPROVER} は部署長の事実から導出されるので通常は起こらないが、
         * 導出を変えたときにここが最後の網になる。
         */
        @Test
        @DisplayName("UT-AUTH-07 APPROVER を持つが部署長でない社員は他人を見られない")
        void approverWithoutManagership() {
            assertThat(visibility.canView(
                    requester(yamada, Role.EMPLOYEE, Role.APPROVER), suzuki, TODAY))
                    .isFalse();
        }

        /**
         * <strong>基準日で判定する。</strong>
         * 就任前の日については見られない。
         * 「今日の組織」で過去を判定すると、就任前の期間まで遡って見えてしまう。
         */
        @Test
        @DisplayName("UT-AUTH-08 就任前の日付については見られない")
        void beforeAppointment() {
            // 長がまだいない部署を作る。既存の部署へ 2 人目を就けると期間が重なり、
            // 本番の DB では EXCLUDE 制約が拒否する状態になってしまう
            var support = org.department("SUPPORT", "サポート部");
            var member = org.hire("E0300", HIRED);
            var newManager = org.hire("E0301", HIRED);
            org.assign(member, support, HIRED);
            org.appoint(support, newManager, LocalDate.of(2026, 4, 1));

            assertThat(visibility.canView(
                    requester(newManager, Role.EMPLOYEE, Role.APPROVER), member,
                    LocalDate.of(2026, 3, 31)))
                    .as("4/1 就任なので 3/31 時点では長ではない").isFalse();
            assertThat(visibility.canView(
                    requester(newManager, Role.EMPLOYEE, Role.APPROVER), member,
                    LocalDate.of(2026, 4, 1)))
                    .isTrue();
        }

        /**
         * 所属の無い社員は、本人と人事しか見られない。
         * <strong>上長を決めようが無いので、承認者へ開かない。</strong>
         */
        @Test
        @DisplayName("UT-AUTH-09 所属の無い社員は承認者から見られない")
        void withoutAssignment() {
            var unassigned = org.hire("E0400", HIRED);

            assertThat(visibility.canView(
                    requester(divisionManager, Role.EMPLOYEE, Role.APPROVER), unassigned,
                    TODAY)).isFalse();
            assertThat(visibility.canView(
                    requester(divisionManager, Role.EMPLOYEE, Role.HR), unassigned, TODAY))
                    .as("人事は所属に関わらず見られる").isTrue();
        }
    }

    /**
     * 上向きと下向きが同じ答えを返すか。
     *
     * <p>同じ規則に<strong>向きの違う 2 つの問い方</strong>がある。
     * 一覧は 1 人ずつ問い（上向き・O(階層の深さ)）、
     * 組織図は見てよい部署をまとめて問う（下向き・O(配下の部署数)）。
     * どちらも要るので 1 つに畳めない（{@link ApproverScope} の javadoc）。
     *
     * <p><strong>畳めないなら、食い違わないことを縛る。</strong>
     * 別々に持ったままだと、規則を変えたときに片方だけ直せてしまい、
     * 「一覧には出るのに組織図に出ない部署」（あるいはその逆）が生まれる。
     * 同じ検査が 2 か所にある状態を「守られている」と読まない（落とし穴 137）。
     */
    @Nested
    @DisplayName("2 つの向きが一致する")
    class BothDirectionsAgree {

        /**
         * <strong>全組み合わせを回す。</strong> 代表を 1 つ選ぶと、
         * その 1 つで偶然そろっているだけの実装を通してしまう（落とし穴 24）。
         */
        @Test
        @DisplayName("UT-AUTH-10 どの承認者とどの社員の組でも、上向きと下向きが一致する")
        void upwardMatchesDownward() {
            var scope = org.approverScope();
            var unassigned = org.hire("E0400", HIRED);
            List<EmployeeId> managers = List.of(sectionManager, divisionManager,
                    yamada, suzuki);
            List<EmployeeId> targets = List.of(yamada, suzuki, sectionManager,
                    divisionManager, unassigned);

            for (EmployeeId manager : managers) {
                Set<DepartmentId> visibleDepartments = scope.departmentsOf(manager, TODAY);
                for (EmployeeId target : targets) {
                    boolean upward = scope.covers(manager, target, TODAY);
                    boolean downward = org.chart().departmentOf(target, TODAY)
                            .map(department -> visibleDepartments.contains(department.id()))
                            .orElse(false);
                    assertThat(upward)
                            .as("上向き %s → %s".formatted(manager.value(), target.value()))
                            .isEqualTo(downward);
                }
            }
        }

        /**
         * <strong>恒真でないことを確かめる。</strong>
         * 上の検査は「どちらも常に false」でも通る。
         * 真になる組と偽になる組が実際にあることを、ここで固定する（落とし穴 36）。
         */
        @Test
        @DisplayName("UT-AUTH-11 一致の検査が恒真でない")
        void bothDirectionsAreExercised() {
            var scope = org.approverScope();

            assertThat(scope.covers(divisionManager, yamada, TODAY))
                    .as("本部長は営業部の山田を配下に持つ").isTrue();
            assertThat(scope.covers(sectionManager, suzuki, TODAY))
                    .as("営業部長は総務部の鈴木を配下に持たない").isFalse();
            assertThat(scope.departmentsOf(divisionManager, TODAY))
                    .as("本部長は本部・営業部・総務部の 3 つ").hasSize(3);
            assertThat(scope.departmentsOf(yamada, TODAY))
                    .as("長を務めていない社員は空").isEmpty();
        }
    }
}
