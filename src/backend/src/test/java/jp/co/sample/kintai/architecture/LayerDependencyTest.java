package jp.co.sample.kintai.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * レイヤの依存方向を強制する（AR-01〜AR-05・AR-08・AR-09）。
 *
 * <p>「ドメイン層は Spring に依存しない」はレビューの気合いでは守れない。
 * ルールとして書き、違反したらビルドを落とす（アーキテクチャ設計書 8 章）。
 *
 * <p><strong>フィールド名は ASCII にする。</strong>
 * ArchUnit はフィールド名をそのままレポートのファイル名にするため、
 * 日本語を使うと locale が POSIX / C の環境（CI のコンテナなど）で
 * レポート生成が「Malformed input」で落ちる。
 * 日本語の説明は Javadoc と {@code because(...)} に書く。失敗時にはそちらが表示される。
 */
@AnalyzeClasses(
        packages = "jp.co.sample.kintai",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    /** AR-01 ドメインはフレームワークから独立している。 */
    @ArchTest
    static final ArchRule AR_01_domain_must_not_depend_on_frameworks =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "com.fasterxml.jackson..",
                            "io.swagger..",
                            "org.hibernate..")
                    .allowEmptyShould(true)
                    .because("ドメインの語彙がフレームワークに縛られると、"
                            + "永続化や API の都合が業務ルールへ染み出す");

    /** AR-02 依存は内側へ向く。 */
    @ArchTest
    static final ArchRule AR_02_domain_must_not_depend_on_outer_layers =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..application..", "..infrastructure..", "..presentation..")
                    .allowEmptyShould(true)
                    .because("ドメインが外側を知ると、依存の向きが逆転して差し替えられなくなる");

    /** AR-03 永続化の実装を差し替え可能に保つ。 */
    @ArchTest
    static final ArchRule AR_03_application_must_not_depend_on_adapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..infrastructure..", "..presentation..")
                    .allowEmptyShould(true)
                    .because("application はポートに対して書く。実装を知ってはいけない");

    /** AR-04 コントローラが JPA エンティティを触らないようにする。 */
    @ArchTest
    static final ArchRule AR_04_presentation_must_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage("..presentation..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                    .allowEmptyShould(true)
                    .because("コントローラが JPA エンティティを直接返すと、"
                            + "テーブルの変更がそのまま API の互換性を壊す");

    /**
     * AR-05 エンティティを実装詳細に閉じ込める。
     *
     * <p>名前の規約（{@code *Entity}）だけでは足りない。規約から外れた名前を付けた瞬間に
     * ルールが素通りする。<strong>{@link jakarta.persistence.Entity} 注釈でも判定する。</strong>
     * 逆に注釈だけでも足りない。エンティティと対になる
     * {@code @Embeddable} や {@code @MappedSuperclass} は注釈が違うので、
     * 名前の規約が最後の網になる。
     */
    @ArchTest
    static final ArchRule AR_05_entities_must_stay_inside_infrastructure =
            noClasses().that().resideOutsideOfPackage("..infrastructure..")
                    .should().dependOnClassesThat(
                            // ★ 名前の規約は自分たちのパッケージにだけ当てる。
                            //   Spring の ResponseEntity も "Entity" で終わる
                            resideInAPackage("jp.co.sample..")
                                    .and(simpleNameEndingWith("Entity"))
                                    .or(annotatedWith(jakarta.persistence.Entity.class)))
                    .allowEmptyShould(true)
                    .because("JPA エンティティはパッケージプライベートに保つ（CLAUDE.md 4.3）");

    /** AR-08 依存はコンストラクタで受け取る。 */
    @ArchTest
    static final ArchRule AR_08_no_field_injection =
            fields().should().notBeAnnotatedWith(
                            "org.springframework.beans.factory.annotation.Autowired")
                    .allowEmptyShould(true)
                    .because("フィールドインジェクションは、テストから依存を差し替えられなくする"
                            + "（CLAUDE.md 4.3）");

    /** AR-09 時刻は Clock 経由で取る。 */
    @ArchTest
    static final ArchRule AR_09_domain_must_not_read_the_clock_directly =
            ArchRuleDefinition.noClasses().that().resideInAPackage("..domain..")
                    .should(callNowWithoutClock())
                    .allowEmptyShould(true)
                    .because("時刻を直接取ると、テストで固定できず境界のケースが書けない"
                            + "（CLAUDE.md 4.3）");

    /**
     * 引数なしの {@code now()} の呼び出しを検出する。
     *
     * <p>{@code LocalDate.now(clock)} のように {@code Clock} を渡す形は許す。
     * 禁じたいのは「どこから時刻が来たか分からない」形だけである。
     */
    private static ArchCondition<JavaClass> callNowWithoutClock() {
        return new ArchCondition<>("java.time の now() を Clock なしで呼ぶ") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    boolean isTimeNow = call.getTargetOwner().getPackageName().equals("java.time")
                            && call.getName().equals("now")
                            && call.getTarget().getRawParameterTypes().isEmpty();
                    if (isTimeNow) {
                        events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                    }
                }
            }
        };
    }
}
