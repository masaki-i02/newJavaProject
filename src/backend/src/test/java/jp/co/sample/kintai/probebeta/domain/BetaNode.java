package jp.co.sample.kintai.probebeta.domain;

import jp.co.sample.kintai.probealpha.domain.AlphaNode;

/**
 * AR-07 違反の戻りの辺。
 *
 * <p>alpha → beta と beta → alpha で循環を作る。
 * domain どうしの参照なので AR-06 には触れない。<strong>循環だけを踏ませる。</strong>
 */
public class BetaNode {

    private final AlphaNode node = new AlphaNode();

    public String read() {
        return node.read();
    }
}
