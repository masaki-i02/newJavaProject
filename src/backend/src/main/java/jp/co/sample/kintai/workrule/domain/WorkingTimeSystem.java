package jp.co.sample.kintai.workrule.domain;

/**
 * 労働時間制度。
 *
 * <p>固定時間制とフレックスタイム制では、同じ打刻データから導かれる結論がまったく異なる。
 * {@code if (isFlextime)} の分岐で表現すると判定漏れが実行時まで分からないので、
 * <strong>{@code sealed interface} にして網羅性検査を効かせる。</strong>
 * 制度を追加した瞬間、分岐している箇所がすべてコンパイルエラーになる。
 */
public sealed interface WorkingTimeSystem permits FixedTimeSystem, FlextimeSystem {
}
