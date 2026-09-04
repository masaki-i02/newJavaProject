package jp.co.sample.kintai.approval.domain;

/**
 * その部署で承認者が決まらなかった理由（BR-11）。
 *
 * <p><strong>部署ごとに残す。</strong>
 * 「なぜこの人が承認者なのか」は運用中に必ず問い合わせが来る。
 * 単一の「決まった部署」だけを持つと、途中でスキップした理由が残らない。
 * 自部署は長が未設定・親では本人、のように経路上に複数の理由が混在しうる。
 */
public enum SkipReason {

    /** ここで承認者が決まった。 */
    NONE,

    /** 部署が廃止されていた。 */
    DEPARTMENT_ABOLISHED,

    /** 長が未設定だった。 */
    NO_MANAGER,

    /** 長が対象の社員本人だった（BR-11 の 4）。 */
    SELF_APPROVAL_AVOIDED,

    /** 長が承認の時点で退職していた。 */
    MANAGER_RETIRED
}
