package jp.co.sample.kintai.payroll.domain;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.workrule.domain.AnnualScheduledHours;

/**
 * 給与連携の出力の記録（BR-18）。
 *
 * <p><strong>「出力済み」を状態として持たないことと、実行の記録を残さないことは別である。</strong>
 * 何度でも出せる（締め済みの値は動かない）が、
 * 全社員の賃金データを外部へ持ち出す操作なので、誰がいつ何を出したかは残す。
 *
 * <p><strong>対象社員を行として持つ。</strong>
 * 値が動かないことと<strong>行の集合が動かないこと</strong>は別である。
 * 記録を作ったあとに人事が残りの社員を締めると、
 * 同じ記録から作り直した CSV の行数が増えてしまい、
 * 記録が「何を出したか」を指さなくなる（CLAUDE.md 落とし穴 122）。
 *
 * @param id                    識別子
 * @param month                 対象月
 * @param exportedBy            実行した人事担当。<strong>社員番号ではなく ID</strong>
 *                              （番号は退職者のぶんが再利用される）
 * @param exportedAt            実行日時。<strong>DB の {@code now()}</strong> が打つ
 * @param divisor               この出力に使った割増賃金の基礎額の分母（労基則 19 条 1 項 4 号）と、
 *                              その導出元。年度の値は後から動くので、支払の根拠として当時の値を残す。
 *                              月平均という<strong>導出値だけでは検算できない</strong>
 * @param targets               対象社員。値が {@code null} なら出力した社員、
 *                              値があれば除外した社員とその理由
 */
public record PayrollExport(
        PayrollExportId id,
        YearMonth month,
        EmployeeId exportedBy,
        Optional<LocalDateTime> exportedAt,
        AnnualScheduledHours divisor,
        Map<EmployeeId, Optional<ExclusionReason>> targets) {

    public PayrollExport {
        if (id == null || month == null || exportedBy == null || exportedAt == null
                || divisor == null || targets == null) {
            throw new IllegalArgumentException("出力の記録の項目に null は許されません");
        }
        if (!divisor.monthlyAverage().isPositive()) {
            throw new IllegalArgumentException(
                    "1 か月平均所定労働時間数は正の値です: " + divisor.monthlyAverage());
        }
        // ★ 対象月が属する年度の分母でなければ、支払の根拠として意味を成さない
        if (divisor.fiscalYear() != AnnualScheduledHours.fiscalYearOf(month)) {
            throw new IllegalArgumentException(
                    "対象月と分母の年度が食い違っています: %s / %d 年度"
                            .formatted(month, divisor.fiscalYear()));
        }
        // ★ 並び順を保つ。Map.copyOf は反復順序を保証しないので、
        //   同じ記録から作り直した CSV の行の順序が変わりうる
        targets = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(targets));
    }

    /** 記録を作る。実行日時は DB が打つので、この時点では空である。 */
    public static PayrollExport of(YearMonth month, EmployeeId exportedBy,
                                   AnnualScheduledHours divisor,
                                   Map<EmployeeId, Optional<ExclusionReason>> targets) {
        return new PayrollExport(PayrollExportId.generate(), month, exportedBy,
                Optional.empty(), divisor, targets);
    }

    /**
     * この出力に使った 1 か月平均所定労働時間数（分）。
     *
     * <p><strong>導出元（年度・年間の所定）を持ち、値そのものは導く。</strong>
     * 月平均だけを残すと、それがどの日数から出たのかを後から言えない。
     */
    public int monthlyAverageMinutes() {
        return (int) divisor.monthlyAverage().toMinutes();
    }

    /** 出力した社員（除外していない社員）。 */
    public List<EmployeeId> includedEmployeeIds() {
        return targets.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 出力した行数。<strong>列として持たない</strong>（2 か所に持つと食い違う）。 */
    public int rowCount() {
        return includedEmployeeIds().size();
    }

    /** 除外した社員の数。 */
    public int excludedCount() {
        return targets.size() - rowCount();
    }
}
