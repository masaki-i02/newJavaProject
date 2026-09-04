package jp.co.sample.kintai.leave.domain;

/**
 * 出勤率（BR-14・労基法 39 条 1 項）。
 *
 * <p>その付与期間の<strong>全労働日</strong>に対する<strong>出勤日</strong>の割合。
 *
 * @param totalWorkingDays   全労働日。所定労働日のうち在籍していた日
 * @param attendedDays       実績から数えた出勤日。労働時間があった日と年休を取得した日
 * @param deemedAttendedDays 人事が申告した出勤扱いの日数（休業・BR-14）
 * @param deemedReason       その根拠。日数が 1 以上なら必須
 */
public record AttendanceRate(int totalWorkingDays, int attendedDays,
                             int deemedAttendedDays, String deemedReason) {

    /** 法定の出勤率（5 分の 4）。 */
    private static final int NUMERATOR = 8;
    private static final int DENOMINATOR = 10;

    public AttendanceRate {
        if (totalWorkingDays < 0 || attendedDays < 0 || deemedAttendedDays < 0) {
            throw new IllegalArgumentException(
                    "出勤率の日数が負です: 全労働日 %d / 出勤 %d / 出勤扱い %d"
                            .formatted(totalWorkingDays, attendedDays, deemedAttendedDays));
        }
        // ★ 和で検査する。各項目が分母以下であることだけを見ると、
        //   出勤 120 + 出勤扱い 40 / 全労働日 150 のような行を作れてしまう
        if (attendedDays + deemedAttendedDays > totalWorkingDays) {
            throw new IllegalArgumentException(
                    "出勤日が全労働日を超えています: %d + %d / %d"
                            .formatted(attendedDays, deemedAttendedDays, totalWorkingDays));
        }
        if (deemedAttendedDays > 0 && (deemedReason == null || deemedReason.isBlank())) {
            throw new IllegalArgumentException("出勤扱いの日数には根拠が必要です");
        }
    }

    /** 出勤扱いの申告が無い出勤率。 */
    public static AttendanceRate of(int totalWorkingDays, int attendedDays) {
        return new AttendanceRate(totalWorkingDays, attendedDays, 0, null);
    }

    /** 出勤扱いを含めた出勤日（BR-14・39 条 10 項）。 */
    public int effectiveAttendedDays() {
        return attendedDays + deemedAttendedDays;
    }

    /**
     * 8 割以上か。
     *
     * <p><strong>整数の掛け算で比べる。</strong>
     * Java の {@code /} は整数除算なので {@code attendedDays / totalWorkingDays} は
     * 120/150 で 0 になる。{@code double} へ寄せれば計算は合うが、
     * 今度は {@code 0.8} という閾値がコードに現れる。
     * <strong>法が定めているのは「5 分の 4」であって浮動小数点数ではない。</strong>
     *
     * <p>全労働日が 0 の期間は「8 割を満たさなかった」とは言えないので、
     * 満たしたものとして扱う。社員に不利な方向へ倒れないようにするためである。
     * <strong>ただしこれは付与日に在籍している社員にしか当てない。</strong>
     * 退職者は在籍期間で絞ると必ず全労働日 0 になるので、
     * 付与の対象から外していないとこの緩和を必ず通ってしまう（落とし穴 92）。
     */
    public boolean meetsThreshold() {
        return totalWorkingDays == 0
                || effectiveAttendedDays() * DENOMINATOR >= totalWorkingDays * NUMERATOR;
    }
}
