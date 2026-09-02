package jp.co.sample.kintai.workrule.domain;

/**
 * 労働時間制度の判別値。<strong>永続化と表示にだけ使う。</strong>
 *
 * <p>業務ロジックの分岐にこの enum を使わない。
 * 分岐は {@link WorkingTimeSystem} に対する網羅性検査つき {@code switch} で行う。
 * enum で分岐すると {@code default} 句が必要になり、
 * 制度を追加したときの考慮漏れが実行時まで分からなくなる。
 */
public enum WorkingTimeSystemType {

    FIXED,
    FLEX;

    /**
     * 制度から判別値を得る。
     *
     * <p>ここは {@code sealed interface} に対する {@code switch} なので、
     * 制度を追加したら最初にこのメソッドがコンパイルエラーになる。
     */
    public static WorkingTimeSystemType of(WorkingTimeSystem system) {
        return switch (system) {
            case FixedTimeSystem ignored -> FIXED;
            case FlextimeSystem ignored -> FLEX;
        };
    }
}
