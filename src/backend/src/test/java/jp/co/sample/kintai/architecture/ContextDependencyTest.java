package jp.co.sample.kintai.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.Optional;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * 業務コンテキスト間の依存を強制する（AR-06・AR-07・AR-10）。
 *
 * <p>許される依存の向きは次のとおり（アーキテクチャ設計書 4 章）。
 * <pre>
 * approval ──&gt; attendance ──&gt; workrule ──&gt; employee
 *        └──────────────────────────────────&gt; employee
 * すべて ──&gt; shared
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

    private static final String ROOT = "jp.co.sample.kintai.";

    /**
     * AR-06 <strong>他</strong>コンテキストの内部（実装・API）には触れない。
     *
     * <p>自コンテキスト内で {@code infrastructure} が {@code Entity} を参照するのは正常なので、
     * 「自分以外の」という条件が要る。ここを落とすと、
     * リポジトリの実装を書いた瞬間にルールが誤って落ちる。
     */
    @ArchTest
    static final ArchRule AR_06_contexts_must_not_reach_into_each_other =
            ArchRuleDefinition.noClasses()
                    .should(dependOnAnotherContextsInternals())
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

    /** AR-10 shared はどのコンテキストにも依存しない。 */
    @ArchTest
    static final ArchRule AR_10_shared_must_not_depend_on_any_context =
            noClasses().that().resideInAPackage("..kintai.shared..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..kintai.employee..", "..kintai.workrule..",
                            "..kintai.attendance..", "..kintai.approval..")
                    .allowEmptyShould(true)
                    .because("shared が個別のコンテキストを知ると、"
                            + "すべてのコンテキストが間接的に結合する");

    private static ArchCondition<JavaClass> dependOnAnotherContextsInternals() {
        return new ArchCondition<>("他コンテキストの infrastructure / presentation に依存する") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<String> own = contextOf(item.getPackageName());
                if (own.isEmpty()) {
                    return;
                }
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getPackageName();
                    Optional<String> other = contextOf(target);
                    boolean crossesContext = other.isPresent() && !other.get().equals(own.get());
                    boolean touchesInternals = target.contains(".infrastructure")
                            || target.contains(".presentation");
                    if (crossesContext && touchesInternals) {
                        events.add(SimpleConditionEvent.satisfied(item,
                                dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** パッケージ名から業務コンテキストの名前を取り出す。 */
    private static Optional<String> contextOf(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return Optional.empty();
        }
        String rest = packageName.substring(ROOT.length());
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        int dot = rest.indexOf('.');
        return Optional.of(dot < 0 ? rest : rest.substring(0, dot));
    }
}
