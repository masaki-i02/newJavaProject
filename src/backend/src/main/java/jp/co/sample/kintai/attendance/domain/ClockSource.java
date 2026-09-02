package jp.co.sample.kintai.attendance.domain;

/**
 * 打刻の発生元。
 *
 * <p><strong>要件に無い値で増やさない。</strong>
 * モバイルアプリ・IC カード・一括取込は要件定義書のどこにも無い。
 * 必要になったら要件を先に改訂する。
 */
public enum ClockSource {

    /** 本人が画面から打刻した。 */
    WEB,

    /** 訂正申請の承認により追記された（BR-09）。理由が必須。 */
    CORRECTION
}
