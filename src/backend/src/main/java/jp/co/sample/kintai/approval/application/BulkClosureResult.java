package jp.co.sample.kintai.approval.application;

import java.time.YearMonth;
import java.util.List;

import jp.co.sample.kintai.approval.domain.AttendanceState;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 一括締めの結果（BR-10）。
 *
 * <p><strong>締められた件数と、締められなかった社員を両方返す。</strong>
 * 締められなかった社員を例外で表すと、100 人のうち 1 人が未承認なだけで
 * 99 人ぶんの締めが失われる。
 *
 * @param month   対象月
 * @param closed  締められた件数
 * @param skipped 締められなかった社員と、その理由
 */
public record BulkClosureResult(YearMonth month, int closed, List<Skipped> skipped) {

    public BulkClosureResult {
        if (month == null || skipped == null) {
            throw new IllegalArgumentException("一括締めの結果に null は許されません");
        }
        if (closed < 0) {
            throw new IllegalArgumentException("締めた件数が負です: " + closed);
        }
        skipped = List.copyOf(skipped);
    }

    /**
     * 締められなかった 1 件。
     *
     * @param employeeId 対象の社員
     * @param state      そのときの状態。<strong>行が無い月は下書き相当</strong>
     * @param reason     人事が次に何をすればよいか分かる理由
     */
    public record Skipped(EmployeeId employeeId, AttendanceState state, String reason) {

        public Skipped {
            if (employeeId == null || state == null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("締められなかった理由が要ります");
            }
        }
    }
}
