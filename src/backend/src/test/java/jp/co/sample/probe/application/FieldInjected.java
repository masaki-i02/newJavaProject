package jp.co.sample.probe.application;

import org.springframework.beans.factory.annotation.Autowired;

import jp.co.sample.probe.infrastructure.ProbeAdapter;

/**
 * AR-08 違反。フィールドインジェクション。
 *
 * <p>ステレオタイプ注釈（{@code @Service} など）は付けない。
 * 付けると本番の走査対象になったときに Bean として登録されてしまう。
 */
public class FieldInjected {

    @Autowired
    private ProbeAdapter adapter;

    public ProbeAdapter adapter() {
        return adapter;
    }
}
