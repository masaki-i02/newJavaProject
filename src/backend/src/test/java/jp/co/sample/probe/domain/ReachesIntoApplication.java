package jp.co.sample.probe.domain;

import jp.co.sample.probe.application.DependsOnAdapter;

/** AR-02 違反。ドメインが application を知っている。 */
public class ReachesIntoApplication {

    private final DependsOnAdapter service = new DependsOnAdapter();

    public String read() {
        return service.read();
    }
}
