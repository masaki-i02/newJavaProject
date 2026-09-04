package jp.co.sample.kintai.approval.domain;

/** 承認者の種別（BR-11）。 */
public enum ApproverKind {

    /** 特定の社員が承認する。 */
    INDIVIDUAL,

    /** 遡っても得られなかったので人事が承認する（BR-11 の 5）。 */
    HUMAN_RESOURCES,

    /**
     * 承認者を問う場面が無い。
     *
     * <p>対象月にまったく所属が無い（入社前・退職後）場合。
     * <strong>その月には月次勤怠が存在しないので、提出も承認も起きない。</strong>
     * BR-11 の 5 に反しているわけではない。
     */
    NONE
}
