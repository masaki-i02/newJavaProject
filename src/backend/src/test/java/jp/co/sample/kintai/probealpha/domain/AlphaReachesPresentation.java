package jp.co.sample.kintai.probealpha.domain;

import jp.co.sample.kintai.probebeta.presentation.BetaResponse;

/**
 * AR-06 違反。<strong>他</strong>コンテキストの {@code presentation} を参照する。
 *
 * <p>{@link AlphaNode}（{@code infrastructure} 側の違反）とは別のクラスにする。
 * 1 つのクラスで両方を踏むと、<strong>片方を禁止先から外しても
 * 同じクラスが引っかかり続ける</strong>ので、削られたことに気づけない。
 */
public class AlphaReachesPresentation {

    private final BetaResponse response = new BetaResponse();

    public String read() {
        return response.render();
    }
}
