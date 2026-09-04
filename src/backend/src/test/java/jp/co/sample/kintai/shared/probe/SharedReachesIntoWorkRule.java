package jp.co.sample.kintai.shared.probe;

import jp.co.sample.kintai.workrule.domain.NightWindow;

/**
 * AR-10 違反。{@code shared} が {@code workrule} を知っている状態。
 *
 * <p>禁止先のコンテキストごとに 1 クラスずつ置く。
 */
public class SharedReachesIntoWorkRule {

    public NightWindow nightWindow() {
        return NightWindow.STANDARD;
    }
}
