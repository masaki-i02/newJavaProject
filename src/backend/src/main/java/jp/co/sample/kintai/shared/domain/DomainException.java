package jp.co.sample.kintai.shared.domain;

import java.io.Serial;

/**
 * 業務ルールに反したことを表す例外の基底。
 *
 * <p>すべてのドメイン例外をここから派生させる。
 * {@code presentation} 層の {@code @RestControllerAdvice} が
 * <strong>この 1 つを捕まえて</strong> RFC 9457 (Problem Details) に変換するため
 * （アーキテクチャ設計書 6.2）。派生させ忘れた例外は 500 になって漏れる。
 *
 * <p>実装の不備（null・桁あふれ・ありえない状態）はここに含めない。
 * それらは {@code IllegalArgumentException} / {@code IllegalStateException} のままにし、
 * 業務エラーとして利用者に見せない。
 */
public abstract class DomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 機械可読なエラー種別。Problem Details の {@code type} に使う。
     *
     * <p>フロントエンドがエラーの種類ごとに振る舞いを変えられるようにする。
     * 文言で分岐させると、メッセージを直した瞬間に画面が壊れる。
     */
    public abstract String errorCode();
}
