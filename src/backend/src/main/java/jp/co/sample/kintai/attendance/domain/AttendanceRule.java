package jp.co.sample.kintai.attendance.domain;

import java.util.List;

/**
 * 労働区間に割増を付与する規則。
 *
 * <p>各規則は「区間リストを受け取り、必要に応じて分割・属性付与した区間リストを返す」
 * 純関数である。合成できるので、適用する規則を差し替えるだけで計算内容が変わる。
 *
 * <p><strong>適用の順序に意味がある。</strong>
 * 分割する規則を先に、属性を付ける規則を後に適用する。
 * <pre>
 * ① CalendarDayBoundaryRule   暦日で切る
 * ② NightWorkRule             深夜帯で切り、NIGHT を付ける
 * ③ LegalHolidayWorkRule      暦日が法定休日の区間に LEGAL_HOLIDAY を付ける
 * ④ DailyOvertimeRule         残りの区間を累積で残業判定する
 * </pre>
 */
public sealed interface AttendanceRule
        permits CalendarDayBoundaryRule, NightWorkRule, LegalHolidayWorkRule, DailyOvertimeRule {

    /** 区間リストに規則を適用する。入力は時系列順である前提。 */
    List<WorkSlice> apply(List<WorkSlice> slices);
}
