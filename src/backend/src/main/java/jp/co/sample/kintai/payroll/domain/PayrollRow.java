package jp.co.sample.kintai.payroll.domain;

import java.time.Duration;
import java.time.YearMonth;

import jp.co.sample.kintai.employee.domain.EmployeeNumber;
import jp.co.sample.kintai.shared.domain.DateRange;
import jp.co.sample.kintai.workrule.domain.WorkingTimeSystemType;

/**
 * 給与計算システムへ渡す 1 行（1 社員 × 1 対象月・BR-18）。
 *
 * <h2>基礎賃金の切り方と、割増の切り方は別である</h2>
 *
 * <p>ここがこのコンテキストでいちばん間違えやすい。
 *
 * <table>
 *   <caption>2 つの切り方</caption>
 *   <tr><th>切り方</th><th>何を分けるか</th><th>列</th></tr>
 *   <tr><td>基礎賃金</td><td>その時間の 1.0 倍が月給に含まれているか</td>
 *       <td>{@code scheduledInsideTime}（含まれる）/ {@code beyondScheduledTime}（含まれない）</td></tr>
 *   <tr><td>割増（労基法 37 条）</td><td>その時間に何 % を上乗せするか</td>
 *       <td>{@code overtimeUpTo60Time} 25% / {@code overtimeOver60Time} 50%
 *           / {@code legalHolidayTime} 35% / {@code nightTime} 深夜 +25%</td></tr>
 * </table>
 *
 * <p><strong>2 つは入れ子になっていない。</strong> 割増の対象時間は、所定内とも所定超とも重なる。
 * 所定総が法定総枠を上回る月では<strong>所定に届いていないのに時間外労働が生じる</strong>ので、
 * 「割増の付かない時間」を所定内と法定内残業へ割ると
 * <strong>基礎賃金を二重に払う</strong>（CLAUDE.md 落とし穴 119）。
 *
 * <pre>
 * 追加で支払う額 = 単価 × 所定超
 *                + 単価 × (0.25×60h まで + 0.50×60h 超 + 0.35×法定休日 + 0.25×深夜)
 * </pre>
 *
 * @param employeeNumber      ① 名寄せの鍵。内部の識別子（UUID）は出さない
 * @param month               ② 対象月
 * @param period              ③ 清算期間。暦月 ∩ 在籍期間（BR-05）。
 *                            <strong>賃金計算期間ではない。</strong> 賃金計算期間は②の暦月である
 * @param system              ④ 労働時間制度
 * @param monthlyScheduledDays ⑤ 暦月の所定労働日数。<strong>日額（月給 ÷ これ）の分母</strong>
 * @param scheduledDays       ⑥ 清算期間の所定労働日数。<strong>年休を引く前</strong>。⑯の根拠
 * @param attendedDays        ⑦ 出勤日数。法定休日・所定休日の出勤を含む（労基則 54 条の労働日数）
 * @param paidLeaveDays       ⑧ 年次有給休暇の取得日数（BR-16）
 * @param absentDays          ⑨ 欠勤日数
 * @param workingTime         ⑩ 実労働時間（労基則 54 条の労働時間数）
 * @param scheduledInsideTime ⑪ 所定内労働時間。月給に含む
 * @param beyondScheduledTime ⑫ 所定超労働時間。<strong>通常の賃金 1.0 倍の追加支払</strong>
 * @param overtimeUpTo60Time  ⑬ 法定外残業（月 60 時間まで）。割増 +25%
 * @param overtimeOver60Time  ⑭ 法定外残業（月 60 時間超）。割増 +50%
 * @param legalHolidayTime    ⑮ 法定休日労働。割増 +35%
 * @param nightTime           ⑯ 深夜労働。割増 +25%（⑪〜⑮に上乗せ）
 * @param scheduledTotalTime  ⑰ 所定総労働時間。年休の日を除いた後
 * @param shortageTime        ⑱ 不足時間
 */
public record PayrollRow(
        EmployeeNumber employeeNumber,
        YearMonth month,
        DateRange period,
        WorkingTimeSystemType system,
        int monthlyScheduledDays,
        int scheduledDays,
        int attendedDays,
        int paidLeaveDays,
        int absentDays,
        Duration workingTime,
        Duration scheduledInsideTime,
        Duration beyondScheduledTime,
        Duration overtimeUpTo60Time,
        Duration overtimeOver60Time,
        Duration legalHolidayTime,
        Duration nightTime,
        Duration scheduledTotalTime,
        Duration shortageTime) {

    public PayrollRow {
        if (employeeNumber == null || month == null || period == null || system == null) {
            throw new IllegalArgumentException("給与連携の行の項目に null は許されません");
        }
        for (Duration value : new Duration[] {workingTime, scheduledInsideTime,
                beyondScheduledTime, overtimeUpTo60Time, overtimeOver60Time,
                legalHolidayTime, nightTime, scheduledTotalTime, shortageTime}) {
            if (value == null) {
                throw new IllegalArgumentException("給与連携の行の時間に null は許されません");
            }
            if (value.isNegative()) {
                throw new IllegalArgumentException("労働時間を負にはできません: " + value);
            }
        }
        for (int value : new int[] {monthlyScheduledDays, scheduledDays, attendedDays,
                paidLeaveDays, absentDays}) {
            if (value < 0) {
                throw new IllegalArgumentException("日数を負にはできません: " + value);
            }
        }

        // ★ 所定内と所定超は実労働を分割する
        if (!scheduledInsideTime.plus(beyondScheduledTime).equals(workingTime)) {
            throw new IllegalArgumentException(
                    "所定内 + 所定超が実労働と一致しません: %s + %s ≠ %s".formatted(
                            scheduledInsideTime, beyondScheduledTime, workingTime));
        }
        // ★ 割増の区分は実労働の内側にある。外から生えるなら計算の誤り
        Duration premium = overtimeUpTo60Time.plus(overtimeOver60Time).plus(legalHolidayTime);
        if (premium.compareTo(workingTime) > 0) {
            throw new IllegalArgumentException(
                    "割増の対象時間が実労働を超えています: %s > %s".formatted(premium, workingTime));
        }
        // ★ 深夜は重複属性だが、実労働の一部ではある（BR-06）
        if (nightTime.compareTo(workingTime) > 0) {
            throw new IllegalArgumentException(
                    "深夜労働が実労働を超えています: %s > %s".formatted(nightTime, workingTime));
        }
        // ★ 所定内労働が所定総を超えることはない
        if (scheduledInsideTime.compareTo(scheduledTotalTime) > 0) {
            throw new IllegalArgumentException(
                    "所定内労働が所定総を超えています: %s > %s"
                            .formatted(scheduledInsideTime, scheduledTotalTime));
        }
        // ★ 不足は所定総に対する不足である
        if (shortageTime.compareTo(scheduledTotalTime) > 0) {
            throw new IllegalArgumentException(
                    "不足時間が所定総を超えています: %s > %s"
                            .formatted(shortageTime, scheduledTotalTime));
        }
        // ★ 清算期間は暦月の部分集合である。日額の分母のほうが必ず大きいか等しい
        if (scheduledDays > monthlyScheduledDays) {
            throw new IllegalArgumentException(
                    "清算期間の所定労働日数が暦月を超えています: %d > %d"
                            .formatted(scheduledDays, monthlyScheduledDays));
        }
        // ★ 年休と欠勤は所定労働日のうちである
        if (paidLeaveDays + absentDays > scheduledDays) {
            throw new IllegalArgumentException(
                    "年休と欠勤の合計が所定労働日数を超えています: %d + %d > %d"
                            .formatted(paidLeaveDays, absentDays, scheduledDays));
        }
        // ★ 出勤日数と所定労働日数の大小は決まっていない。
        //   法定休日・所定休日に出勤した日は出勤日数に数えるが所定労働日数には入らないので、
        //   出勤日数のほうが多い月は適法に存在する（落とし穴 23・51）。検査を置いてはならない
    }
}
