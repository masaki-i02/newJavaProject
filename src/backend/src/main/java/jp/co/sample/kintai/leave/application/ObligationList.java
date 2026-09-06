package jp.co.sample.kintai.leave.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 年 5 日の取得義務の一覧（BR-17）。
 *
 * <p><strong>解決した基準日を一緒に返す。</strong>
 * 既定値は {@code application} 層が {@code Clock} から決めるので（AR-09）、
 * 返さないと利用者は「いつ時点の一覧なのか」を確かめられない。
 */
public record ObligationList(LocalDate asOf, List<ObligationSummary> items) {

    public ObligationList {
        if (asOf == null || items == null) {
            throw new IllegalArgumentException("取得義務の一覧に null は許されません");
        }
        items = List.copyOf(items);
    }
}
