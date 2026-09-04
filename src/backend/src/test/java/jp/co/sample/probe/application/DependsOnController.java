package jp.co.sample.probe.application;

import jp.co.sample.probe.presentation.ProbeView;

/**
 * AR-03 違反。{@code application} が {@code presentation} に依存する。
 *
 * <p>{@link DependsOnAdapter}（{@code infrastructure} 側の違反）とは別のクラスにする。
 * AR-03 は 2 つを禁じているので、1 つのクラスで両方を踏むと
 * <strong>片方を禁止先から外しても同じクラスが引っかかり続ける。</strong>
 */
public class DependsOnController {

    private final ProbeView view = new ProbeView();

    public String show() {
        return view.render();
    }
}
