package jp.co.sample.kintai.approval.domain;

/**
 * 訂正申請の状態（BR-09）。
 *
 * <p><strong>却下と取下げを分ける。</strong>
 * 却下は承認者の判断、取下げは本人の意思である。
 * 証跡で区別できないと「何度も却下されている社員」という誤読が生まれる。
 */
public enum CorrectionStatus {

    /** 申請済み。承認待ち。 */
    SUBMITTED,

    /** 承認された。打刻が書き換わっている。 */
    APPROVED,

    /** 却下された（承認者の判断。理由が必須）。 */
    REJECTED,

    /** 取り下げられた（本人の意思）。 */
    CANCELED;

    /** まだ決着していないか。<strong>同一勤務日に 2 件は作れない</strong>のがこの状態。 */
    public boolean isPending() {
        return this == SUBMITTED;
    }
}
