package jp.co.sample.kintai.leave.application;

import java.time.LocalDate;
import java.util.List;

import jp.co.sample.kintai.leave.domain.AttendanceRate;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 付与の実行結果（BR-14）。
 *
 * <p><strong>「一括」の失敗を 1 種類にしない</strong>（CLAUDE.md 落とし穴 60）。
 * 社員ごとの事情は結果へ、依頼そのものの不備（人事でない）は例外へ分ける。
 *
 * <p><strong>{@code withheld} を {@code skipped} と分ける。</strong>
 * 前者は法どおりの不付与、後者は既に処理済みという運用上の事実であり、
 * 人事が取るべき行動が違う。
 */
public record GrantResult(LocalDate asOf, List<Granted> granted, List<Withheld> withheld,
                          List<Skipped> skipped) {

    public GrantResult {
        granted = List.copyOf(granted);
        withheld = List.copyOf(withheld);
        skipped = List.copyOf(skipped);
    }

    /** 付与した。 */
    public record Granted(EmployeeId employeeId, LocalDate grantedOn, int days) {
    }

    /** 出勤率 8 割に満たないので付与しなかった（BR-14）。 */
    public record Withheld(EmployeeId employeeId, LocalDate grantedOn, AttendanceRate rate) {
    }

    /** 既に処理済みなので何もしなかった。 */
    public record Skipped(EmployeeId employeeId, LocalDate grantedOn, String reason) {

        /** 同じ付与が既にある。付与処理は冪等である。 */
        public static final String ALREADY_GRANTED = "already-granted";
    }
}
