package jp.co.sample.kintai.approval.domain;

/**
 * 監査証跡に残す遷移の種類。
 *
 * <p>DB の {@code approval_events_kind_check} と一対一で対応する。
 *
 * <p><strong>{@code REJECT} と {@code REVERT_BY_CORRECTION} を分ける。</strong>
 * どちらも提出済 → 下書きだが、差戻しは承認者の判断で、
 * 訂正による巻き戻しは内容が変わったことによるもので<strong>本人に非が無い。</strong>
 * 証跡で区別できないと「何度も差し戻されている社員」という誤った読み取りが生まれる。
 */
public enum ApprovalEventKind {

    SUBMIT,

    /** 退職者の最終月を人事が代理提出した。<strong>理由が必須。</strong> */
    PROXY_SUBMIT,

    APPROVE,

    /** 承認者による差戻し。<strong>理由が必須。</strong> */
    REJECT,

    CLOSE,

    /** 人事による承認の取消。<strong>理由が必須。</strong> */
    REVOKE_APPROVAL,

    /** 訂正申請の承認による自動差戻し。<strong>理由が必須。</strong> */
    REVERT_BY_CORRECTION
}
