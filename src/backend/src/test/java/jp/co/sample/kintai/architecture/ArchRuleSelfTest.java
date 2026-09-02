package jp.co.sample.kintai.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit のルールが<strong>本当に違反を捕まえること</strong>を確かめる。
 *
 * <p>ルールが緑なのは「規約が守られている」からとは限らない。
 * 対象が 1 件も無い、条件が誤っていて何にも当たらない、という理由でも緑になる。
 * とくに実装が進んでいない段階では {@code allowEmptyShould(true)} を付けているので、
 * <strong>ルールは黙って何も検査しないでいられる</strong>（CLAUDE.md 落とし穴 30・31）。
 *
 * <p>そこで、わざと違反したクラス（{@code jp.co.sample.probe} と
 * {@code jp.co.sample.kintai.probealpha} / {@code probebeta}）を用意し、
 * 同じルールをそれに当てて<strong>落ちること</strong>を確かめる。
 * ルールを緩めた瞬間に、このテストが落ちる。
 *
 * <p>違反クラスはテストソースにしか無く、本番のルールは
 * {@code ImportOption.DoNotIncludeTests} で除外しているので、本番の検査は汚さない。
 * <strong>その除外自体もここで確かめる</strong>（最後のテスト）。
 */
@DisplayName("ArchUnit ルールの自己検査")
class ArchRuleSelfTest {

    /** 層のルール用。{@code jp.co.sample.kintai} の外に置いてある。 */
    private static final JavaClasses LAYER_PROBES =
            new ClassFileImporter().importPackages("jp.co.sample.probe");

    /** コンテキストのルール用。コンテキスト名を取り出す都合で {@code kintai} の下に要る。 */
    private static final JavaClasses CONTEXT_PROBES = new ClassFileImporter()
            .importPackages("jp.co.sample.kintai.probealpha", "jp.co.sample.kintai.probebeta");

    private static final JavaClasses SHARED_PROBES =
            new ClassFileImporter().importPackages("jp.co.sample.kintai.shared");

    @Nested
    @DisplayName("層のルール")
    class Layers {

        /**
         * 禁止するパッケージを 1 つずつ踏む違反クラスを置く。
         *
         * <p><strong>1 つだけ踏んでも足りない。</strong>
         * 禁止先を 5 個から 1 個に減らしても、そのうち 1 個しか踏んでいなければ
         * 自己検査は落ちない。実際、5 個を {@code org.springframework..} だけに
         * 削っても 288 件が通る状態だった。
         */
        @Test
        @DisplayName("AR-01 禁止したフレームワークをどれか 1 つでも使ったら落ちる")
        void ar01() {
            assertFails(LayerDependencyTest.AR_01_domain_must_not_depend_on_frameworks,
                    LAYER_PROBES, "UsesSpringFramework", "UsesJackson",
                    "UsesJakartaPersistence", "UsesHibernate");
        }

        @Test
        @DisplayName("AR-02 ドメインが外側の 3 層のどれに依存しても落ちる")
        void ar02() {
            assertFails(LayerDependencyTest.AR_02_domain_must_not_depend_on_outer_layers,
                    LAYER_PROBES, "ReachesIntoInfrastructure", "ReachesIntoApplication",
                    "ReachesIntoPresentation");
        }

        @Test
        @DisplayName("AR-03 application が実装に依存したら落ちる")
        void ar03() {
            assertFails(LayerDependencyTest.AR_03_application_must_not_depend_on_adapters,
                    LAYER_PROBES, "DependsOnAdapter");
        }

        @Test
        @DisplayName("AR-04 presentation が infrastructure に依存したら落ちる")
        void ar04() {
            assertFails(LayerDependencyTest.AR_04_presentation_must_not_depend_on_infrastructure,
                    LAYER_PROBES, "ReturnsEntity");
        }

        /**
         * 名前の規約と注釈の<strong>両方</strong>が効いていることを確かめる。
         *
         * <p>1 個の probe に両方を持たせると、どちらの条件で落ちたのか区別できない。
         * 名前だけの {@code ProbeEntity} と注釈だけの {@code LegacyRow} に分けてある。
         */
        @Test
        @DisplayName("AR-05 名前の規約でも注釈でもエンティティの漏れを捕まえる")
        void ar05() {
            assertFails(LayerDependencyTest.AR_05_entities_must_stay_inside_infrastructure,
                    LAYER_PROBES, "ProbeEntity", "LegacyRow");
        }

        @Test
        @DisplayName("AR-08 フィールドインジェクションがあれば落ちる")
        void ar08() {
            assertFails(LayerDependencyTest.AR_08_no_field_injection,
                    LAYER_PROBES, "FieldInjected");
        }

        @Test
        @DisplayName("AR-09 Clock なしの now() があれば落ちる")
        void ar09() {
            assertFails(LayerDependencyTest.AR_09_domain_must_not_read_the_clock_directly,
                    LAYER_PROBES, "CallsNowWithoutClock");
        }
    }

    @Nested
    @DisplayName("業務コンテキストのルール")
    class Contexts {

        @Test
        @DisplayName("AR-06 他コンテキストの infrastructure に触れたら落ちる")
        void ar06() {
            assertFails(ContextDependencyTest.AR_06_contexts_must_not_reach_into_each_other,
                    CONTEXT_PROBES, "BetaAdapter");
        }

        @Test
        @DisplayName("AR-07 コンテキストが循環したら落ちる")
        void ar07() {
            assertFails(ContextDependencyTest.AR_07_context_dependencies_must_be_acyclic,
                    CONTEXT_PROBES, "Cycle");
        }

        @Test
        @DisplayName("shared がコンテキストを参照したら落ちる")
        void sharedMustNotDependOnContexts() {
            assertFails(ContextDependencyTest.shared_must_not_depend_on_any_context,
                    SHARED_PROBES, "SharedReachesIntoContext");
        }

        /**
         * AR-06 が「他コンテキストかどうか」を見ていることを確かめる。
         *
         * <p>ここを見落とすと、リポジトリの実装が自分の {@code Entity} を参照した瞬間に
         * ルールが誤って落ちる。<strong>落ちないことにも意味がある。</strong>
         */
        @Test
        @DisplayName("AR-06 自コンテキスト内の infrastructure 参照では落ちない")
        void ar06AllowsOwnContext() {
            JavaClasses ownContextOnly =
                    new ClassFileImporter().importPackages("jp.co.sample.kintai.probebeta");
            assertThatCode(() ->
                    ContextDependencyTest.AR_06_contexts_must_not_reach_into_each_other
                            .check(ownContextOnly))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * 違反クラスが本番の検査に混ざっていないことを確かめる。
     *
     * <p>{@code DoNotIncludeTests} が効かなくなると、ここで用意した違反が
     * 本番のルールを落とし、<strong>ルール全体を無効化する圧力になる</strong>。
     */
    @Test
    @DisplayName("違反クラスは本番の検査対象に入っていない")
    void probesAreExcludedFromProductionAnalysis() {
        assertThatCode(() -> {
            LayerDependencyTest.AR_01_domain_must_not_depend_on_frameworks.check(production());
            LayerDependencyTest.AR_05_entities_must_stay_inside_infrastructure.check(production());
            ContextDependencyTest.AR_06_contexts_must_not_reach_into_each_other.check(production());
        }).doesNotThrowAnyException();
    }

    private static JavaClasses production() {
        return new ClassFileImporter()
                .withImportOption(new com.tngtech.archunit.core.importer.ImportOption
                        .DoNotIncludeTests())
                .importPackages("jp.co.sample.kintai");
    }

    /**
     * ルールが落ち、しかも<strong>期待した違反をすべて報告する</strong>ことを確かめる。
     *
     * <p>「落ちた」だけを見ると、条件を 1 つ残して他を削ったルールでも通ってしまう。
     * 条件ごとに違反クラスを置き、そのすべてが報告に現れることを求める。
     */
    private static void assertFails(ArchRule rule, JavaClasses probes,
                                    String... expectedInMessage) {
        assertThatThrownBy(() -> rule.check(probes))
                .as("%s は違反を捕まえなければならない", rule.getDescription())
                .isInstanceOf(AssertionError.class)
                .hasMessageContainingAll(expectedInMessage);
    }
}
