package jp.co.sample.kintai.shared.domain;

import java.util.Map;

/**
 * Problem Details に載せる追加の項目を持つ業務エラー。
 *
 * <p>「未計算の日が 2026-05-12 と 2026-05-20 にある」のように、
 * <strong>利用者がどこを直せばよいかを、例外の側が知っている</strong>場合がある。
 * メッセージの文章へ埋め込むだけだと、画面が機械的に扱えない。
 *
 * <p>{@code presentation} 層はこの型だけを見て項目を写す。
 * <strong>例外の種類ごとにハンドラを増やさない。</strong>
 * 増やすと、新しい例外を足したときにハンドラを書き忘れて情報が落ちる。
 */
public interface DetailedDomainException {

    /** Problem Details へ載せる項目。キーはそのまま JSON の項目名になる。 */
    Map<String, Object> properties();
}
