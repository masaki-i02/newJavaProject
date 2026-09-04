package jp.co.sample.kintai.leave.domain;

/**
 * 年休の申請の遷移の種類。監査証跡に残す。
 *
 * <p>DB の {@code paid_leave_request_events_kind_check} と一対一で対応する。
 *
 * <p><strong>{@code CANCEL} と {@code REVOKE} を分ける。</strong>
 * どちらも承認済みから取消へ向かうが、前者は本人が予定を変えたもの、
 * 後者は取得日を過ぎたあとの是正である。
 * 区別できないと「頻繁に年休を取り消す社員」という誤った読み取りが生まれる。
 */
public enum LeaveRequestEventKind {

    SUBMIT,

    APPROVE,

    /** 承認者による却下。<strong>理由が必須。</strong> */
    REJECT,

    /** 本人による取下げ。 */
    CANCEL,

    /** 取得日の当日以降に人事が取り消した（BR-16）。<strong>理由が必須。</strong> */
    REVOKE
}
