package jp.co.sample.kintai.attendance.domain.monthly;

/**
 * 給与へ渡す日数（BR-18 の⑤⑥⑦⑧⑨）。
 *
 * <p><strong>所定労働日数を 2 つ持つ。</strong>
 * 給与側が日額（月給 ÷ 所定労働日数）の分母に使うのは<strong>暦月</strong>の所定労働日数であり、
 * 所定総労働時間の根拠になるのは<strong>清算期間</strong>（暦月 ∩ 在籍期間）のほうである。
 * 月中入社・月中退職の月ではこの 2 つが一致せず、
 * 清算期間のほうを分母に使うと<strong>控除が 2 倍近くに膨らむ</strong>（労基法 24 条の全額払い）。
 * しかも暦月の所定労働日数は他のどの項目からも復元できないので、両方を渡す。
 *
 * <p><strong>所定労働日数は年休を引く前の値である。</strong>
 * 給与側は日額（月給 ÷ 所定労働日数）で欠勤控除するので、
 * 控除後を渡すと<strong>年休を取った月ほど 1 日あたりの控除が大きくなる</strong>。
 * 所定総労働時間の根拠になる「年休を除いた所定労働日数」は {@code scheduledDays − paidLeaveDays}
 * で復元できる。
 *
 * <p><strong>出勤日数と所定労働日数の大小は決まっていない。</strong>
 * 法定休日・所定休日に出勤した日は出勤日数に数えるが所定労働日数には入らないので、
 * 出勤日数のほうが多い月は適法に存在する（CLAUDE.md 落とし穴 23・51）。
 * だから欠勤日数を引き算で導かせず、別に数える。
 *
 * @param monthlyScheduledDays 暦月の所定労働日数。<strong>在籍期間で切らない。</strong>
 *                             給与側の日額の分母（労基法 24 条）
 * @param scheduledDays  清算期間（暦月 ∩ 在籍期間）の所定労働日数。<strong>年休を引く前</strong>
 * @param attendedDays   実労働が 1 分でもある勤務日の数。法定休日・所定休日の出勤を含む
 * @param paidLeaveDays  承認済みの年次有給休暇の取得日数（BR-16）
 * @param absentDays     所定労働日のうち、年休でもなく実労働が 1 分も無い日数
 */
public record MonthlyDayCounts(int monthlyScheduledDays, int scheduledDays, int attendedDays,
                               int paidLeaveDays, int absentDays) {

    public MonthlyDayCounts {
        for (int value : new int[] {monthlyScheduledDays, scheduledDays, attendedDays,
                paidLeaveDays, absentDays}) {
            if (value < 0) {
                throw new IllegalArgumentException("日数を負にはできません: " + value);
            }
        }
        // ★ 清算期間は暦月の部分集合なので、その所定労働日数も超えない
        if (scheduledDays > monthlyScheduledDays) {
            throw new IllegalArgumentException(
                    "清算期間の所定労働日数が暦月を超えています: 清算期間 %d / 暦月 %d"
                            .formatted(scheduledDays, monthlyScheduledDays));
        }
        // ★ 年休の日は所定労働日の一部である。超えるなら数え方の誤り
        if (paidLeaveDays > scheduledDays) {
            throw new IllegalArgumentException(
                    "年休の日数が所定労働日数を超えています: 年休 %d / 所定労働日 %d"
                            .formatted(paidLeaveDays, scheduledDays));
        }
        // ★ 欠勤も所定労働日のうちであり、年休と重ならない
        if (paidLeaveDays + absentDays > scheduledDays) {
            throw new IllegalArgumentException(
                    "年休と欠勤の合計が所定労働日数を超えています: 年休 %d + 欠勤 %d > 所定労働日 %d"
                            .formatted(paidLeaveDays, absentDays, scheduledDays));
        }
    }
}
