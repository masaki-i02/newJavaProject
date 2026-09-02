package jp.co.sample.kintai.architecture;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 業務コンテキスト間の依存を強制する（AR-06・AR-07）。
 *
 * <p>許される依存の向きは次のとおり（アーキテクチャ設計書 4 章）。
 * <pre>
 * approval ──> attendance ──> workrule ──> employee
 *        └──────────────────────────────────> employee
 * すべて ──> shared
 * </pre>
 *
 * <p>逆向きの問い合わせが必要になったら、
 * <strong>ポートを {@code shared.domain} に置いて解決する</strong>（ADR 0004）。
 * 締め状態の判定（{@code MonthClosureQuery}）がその形である。
 */
@AnalyzeClasses(
        packages = "jp.co.sample.kintai",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ContextDependencyTest {

    /** AR-06 他コンテキストの内部（実装・API）には触れない。 */
    @ArchTest
    static final ArchRule AR_06_contexts_must_not_reach_into_each_other =
            noClasses().that().resideInAPackage("jp.co.sample.kintai.(*)..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..infrastructure..", "..presentation..")
                    .allowEmptyShould(true)
                    .because("他コンテキストの実装や API に触れると、"
                            + "内部の変更が境界を越えて伝播する。参照してよいのは domain だけ");

    /** AR-07 コンテキスト間の依存に循環がない。 */
    @ArchTest
    static final ArchRule AR_07_context_dependencies_must_be_acyclic =
            slices().matching("jp.co.sample.kintai.(*)..")
                    .should().beFreeOfCycles()
                    .allowEmptyShould(true)
                    .because("循環すると分割の意味が無くなる。"
                            + "PremiumType と締め判定はこれを避けるために shared へ置いた（ADR 0004）");

    /** shared はどのコンテキストにも依存しない。 */
    @ArchTest
    static final ArchRule shared_must_not_depend_on_any_context =
            noClasses().that().resideInAPackage("..kintai.shared..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..kintai.employee..", "..kintai.workrule..",
                            "..kintai.attendance..", "..kintai.approval..")
                    .allowEmptyShould(true)
                    .because("shared が個別のコンテキストを知ると、"
                            + "すべてのコンテキストが間接的に結合する");
}
