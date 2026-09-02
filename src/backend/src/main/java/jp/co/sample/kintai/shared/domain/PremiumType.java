package jp.co.sample.kintai.shared.domain;

/**
 * 割増の区分（BR-04 / BR-06 / BR-07）。
 *
 * <p><strong>{@code shared} に置く。</strong>
 * 割増率を持つ {@code workrule} と、区間へ区分を付ける {@code attendance} の
 * 双方が使うため、どちらかに置くと依存が循環する（ADR 0004）。
 */
public enum PremiumType {

    /** 法定内残業。所定は超えるが法定内。割増の支払義務は無い。 */
    OVERTIME_WITHIN_STATUTORY,

    /** 法定外残業。25% 以上。 */
    OVERTIME_BEYOND_STATUTORY,

    /** 深夜。25% 以上。<strong>他の区分に重ねて付く。</strong> */
    NIGHT,

    /** 法定休日労働。35% 以上。 */
    LEGAL_HOLIDAY;

    /**
     * 実労働時間を排他的に分割する区分か。
     *
     * <p><strong>{@code NIGHT} だけは他に重ねて付く属性であり、合計には数えない。</strong>
     * ここを間違えると「深夜だけが付いた基本時間の区間」をどの区分にも数え損ね、
     * 内訳の合計が実労働時間と一致しなくなる。
     *
     * <p><strong>{@code this != NIGHT} と書かない。</strong>
     * 区分を追加したときに黙って「排他区分」に分類され、
     * 内訳の合計が壊れる形でしか気づけなくなる。
     * {@code default} 句の無い {@code switch} にしておけば、
     * 追加した瞬間にコンパイルエラーになる。
     */
    public boolean partitionsWorkingTime() {
        return switch (this) {
            case NIGHT -> false;
            case OVERTIME_WITHIN_STATUTORY, OVERTIME_BEYOND_STATUTORY, LEGAL_HOLIDAY -> true;
        };
    }
}
