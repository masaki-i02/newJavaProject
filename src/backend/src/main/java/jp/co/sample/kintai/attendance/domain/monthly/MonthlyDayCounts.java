package jp.co.sample.kintai.attendance.domain.monthly;

/**
 * 給与へ渡す日数（BR-18 の⑤⑥⑦⑧）。
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
 * @param scheduledDays  清算期間の所定労働日数。<strong>年休を引く前</strong>
 * @param attendedDays   実労働が 1 分でもある勤務日の数。法定休日・所定休日の出勤を含む
 * @param paidLeaveDays  承認済みの年次有給休暇の取得日数（BR-16）
 * @param absentDays     所定労働日のうち、年休でもなく実労働が 1 分も無い日数
 */
public record MonthlyDayCounts(int scheduledDays, int attendedDays,
                               int paidLeaveDays, int absentDays) {

    public MonthlyDayCounts {
        for (int value : new int[] {scheduledDays, attendedDays, paidLeaveDays, absentDays}) {
            if (value < 0) {
                throw new IllegalArgumentException("日数を負にはできません: " + value);
            }
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
