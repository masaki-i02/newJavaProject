package jp.co.sample.kintai.approval.domain;

import java.io.Serial;

import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;

/**
 * 実行者が BR-11 の承認者ではない。
 *
 * <p><strong>一般の権限不足（{@code forbidden}）と分ける。</strong>
 * 承認者は組織と基準日から導かれるので、
 * 「ロールが足りない」のではなく「その月のその社員の承認者ではない」である。
 * 利用者への案内がまったく違う（誰に頼めばよいかを聞く先が変わる）。
 *
 * <p><strong>自己承認（{@code self-approval}）とも分ける。</strong>
 * まとめると、自己承認の禁止を消してもテストが 1 件も落ちない
 * （CLAUDE.md 落とし穴 58）。
 *
 * <p>誰が承認者なのかは載せない。承認者の氏名は {@code employee} が所有する概念であり、
 * 画面は {@code GET .../approver} で別途引く。
 */
public final class NotApproverException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotApproverException() {
        super("この勤怠の承認者ではありません");
    }

    @Override
    public String errorCode() {
        return "urn:kintai:error:not-approver";
    }

    @Override
    public DomainErrorKind kind() {
        return DomainErrorKind.FORBIDDEN;
    }

    @Override
    public String title() {
        return "承認者ではありません";
    }
}
