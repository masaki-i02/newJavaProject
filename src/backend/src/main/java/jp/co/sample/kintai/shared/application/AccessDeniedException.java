package jp.co.sample.kintai.shared.application;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 閲覧範囲または権限が足りない。
 *
 * <p><strong>「対象が存在しない」と区別して返す。</strong>
 * 存在の有無を隠したい API では 404 に寄せる設計もあるが、
 * 本システムの対象は社員 100 名の社内システムであり、
 * 社員が実在すること自体は隠す情報ではない。
 * 403 で返した方が「権限が足りない」と分かって問い合わせが減る。
 *
 * <p>対象の氏名や部署は載せない。<strong>見られない相手の情報を、
 * 見られない理由の説明として渡さない。</strong>
 */
public final class AccessDeniedException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AccessDeniedException() {
        super("この操作を行う権限がありません");
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:forbidden";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.FORBIDDEN;
    }

    @Override
    public String title() {
        return "権限がありません";
    }
}
