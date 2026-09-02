package jp.co.sample.probe.domain;

import jp.co.sample.probe.presentation.ReturnsEntity;

/** AR-02 違反。ドメインが presentation を知っている。 */
public class ReachesIntoPresentation {

    private final ReturnsEntity controller = new ReturnsEntity();

    public String read() {
        return controller.read();
    }
}
