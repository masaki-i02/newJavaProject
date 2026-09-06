package jp.co.sample.kintai.leave.domain;

import java.io.Serial;
import java.time.LocalDate;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/** 付与済みの付与を再判定しようとした（BR-14）。 */
public final class AlreadyGrantedException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * <p><strong>公開する。</strong> アプリケーション層が、人事の入力を検証するより先に
     * この拒否を返すために使う。集約の中でも同じ検査を残すのは、
     * アプリケーション層を通さない経路を塞ぐためである（落とし穴 58）。
     */
    public AlreadyGrantedException(LocalDate grantedOn) {
        super("%s の付与は既に付与済みです。再判定できるのは不付与だった付与だけです"
                .formatted(grantedOn));
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:grant-already-granted";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.CONFLICT;
    }

    @Override
    public String title() {
        return "既に付与済みです";
    }
}
