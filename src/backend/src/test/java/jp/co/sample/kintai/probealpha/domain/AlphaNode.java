package jp.co.sample.kintai.probealpha.domain;

import jp.co.sample.kintai.probebeta.infrastructure.BetaAdapter;

/**
 * AR-06 違反。<strong>他</strong>コンテキストの infrastructure を参照する。
 *
 * <p>AR-07（循環）の一方の辺でもある。
 */
public class AlphaNode {

    private final BetaAdapter adapter = new BetaAdapter();

    public String read() {
        return adapter.load();
    }
}
