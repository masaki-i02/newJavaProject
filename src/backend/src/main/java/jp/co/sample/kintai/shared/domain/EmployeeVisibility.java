package jp.co.sample.kintai.shared.domain;

import java.time.LocalDate;

/**
 * ある社員の情報を、誰が見てよいか（要件定義書 4.1）。
 *
 * <p><strong>ポートを {@code shared.domain} に置き、実装を提供側（{@code employee}）に置く。</strong>
 * 判断には組織（誰が誰の上長か）が要るが、勤怠の各コンテキストが
 * {@code employee} の内部を直接引くと依存の辺が増える（ADR 0004）。
 *
 * <p><strong>ロールだけでは決まらない。</strong>
 * {@code APPROVER} の範囲は「自分が長を務める部署の配下」であり、
 * 組織の状態と基準日に依存する。だから Spring Security の認可設定には置けない。
 */
public interface EmployeeVisibility {

    /**
     * 依頼者が対象の社員を見てよいか。
     *
     * @param asOf 基準日。<strong>組織は日によって変わる</strong>ので必ず受け取る
     */
    boolean canView(Requester requester, EmployeeId target, LocalDate asOf);
}
