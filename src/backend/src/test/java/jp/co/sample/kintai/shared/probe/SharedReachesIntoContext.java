package jp.co.sample.kintai.shared.probe;

import jp.co.sample.kintai.workrule.domain.NightWindow;

/** shared が個別のコンテキストを知っている状態。 */
public class SharedReachesIntoContext {

    public NightWindow nightWindow() {
        return NightWindow.STANDARD;
    }
}
