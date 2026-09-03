package jp.co.sample.kintai.shared.domain;

/**
 * 業務エラーの種別。
 *
 * <p><strong>HTTP のステータスをドメインに持たせないための型である。</strong>
 * ドメインはフレームワークを知らない（AR-01）ので、
 * {@code HttpStatus} を返すメソッドを {@link DomainException} に置けない。
 * かわりに業務上の意味だけを分類し、HTTP への対応づけは {@code presentation} が行う。
 *
 * <p>{@code presentation} 側の変換は {@code default} 句の無い {@code switch} で書く。
 * <strong>種別を足した瞬間にそこがコンパイルエラーになる。</strong>
 * 対応表を {@code errorCode()} の文字列で引く形にすると、
 * 追加した例外が既定のステータス（多くは 500）で黙って漏れる。
 */
public enum DomainErrorKind {

    /** 形式は正しいが、法令・就業規則の定めに反する。入力を直せば通る。 */
    RULE_VIOLATION,

    /** 現在の状態では受け付けられない。状態が変われば通る（退勤前・締め済みなど）。 */
    CONFLICT,

    /** 対象が存在しない。 */
    NOT_FOUND,

    /** 権限または閲覧範囲が足りない。 */
    FORBIDDEN
}
