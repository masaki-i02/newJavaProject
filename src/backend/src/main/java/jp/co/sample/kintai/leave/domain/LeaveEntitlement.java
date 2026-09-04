package jp.co.sample.kintai.leave.domain;

/**
 * 継続勤務年数と付与日数の対応（BR-14・労基法 39 条 2 項）。
 *
 * <p><strong>取りうる値を法が固定しているので {@code enum} にする</strong>（CLAUDE.md 4.3）。
 * {@code record} + 実行時検証だと、型の上では 3 日でも 100 日でも渡せる。
 *
 * <p>比例付与（39 条 3 項）は対象外である（BR-14）。
 * 全社員の所定労働時間が 1 日 8 時間なので、該当者が存在しない。
 */
public enum LeaveEntitlement {

    MONTHS_6(6, 10),
    YEARS_1_6(18, 11),
    YEARS_2_6(30, 12),
    YEARS_3_6(42, 14),
    YEARS_4_6(54, 16),
    YEARS_5_6(66, 18),
    /** 6 年 6 か月以降は 20 日で頭打ち（39 条 2 項の表の末行）。 */
    YEARS_6_6(78, 20);

    /** 法定の付与日数の下限。10 日未満は比例付与の領分であり、本システムには存在しない。 */
    public static final int MIN_DAYS = 10;

    /** 法定の付与日数の上限。 */
    public static final int MAX_DAYS = 20;

    private final int monthsOfService;
    private final int days;

    LeaveEntitlement(int monthsOfService, int days) {
        this.monthsOfService = monthsOfService;
        this.days = days;
    }

    /**
     * 何回目の付与か（0 起点）から引く。
     *
     * <p>7 回目以降はすべて 20 日になる。
     * <strong>8 割未満で不付与だった年も連番は進む</strong>（BR-14）。
     * 止めると、一度欠勤の多い年があった社員の付与日数が永久に低いままになる。
     */
    public static LeaveEntitlement of(int grantIndex) {
        if (grantIndex < 0) {
            throw new IllegalArgumentException("付与の連番が負です: " + grantIndex);
        }
        LeaveEntitlement[] all = values();
        return all[Math.min(grantIndex, all.length - 1)];
    }

    /** 継続勤務年数（月数）。 */
    public int monthsOfService() {
        return monthsOfService;
    }

    /** 付与日数。 */
    public int days() {
        return days;
    }
}
