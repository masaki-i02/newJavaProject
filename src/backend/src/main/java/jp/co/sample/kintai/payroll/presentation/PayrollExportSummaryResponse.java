package jp.co.sample.kintai.payroll.presentation;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import jp.co.sample.kintai.payroll.domain.PayrollExport;

/**
 * 出力の記録（API設計書 4）。
 *
 * <p><strong>実行者の社員番号・氏名を返さない。</strong>
 * 誰が実行したかは画面の関心であり、{@code employee} から引けばよい。
 * CSV と除外一覧が社員番号を持つのは給与処理の対象として必要だからで、
 * 監査の照会はそれに当たらない（API設計書 1.2）。
 */
public record PayrollExportSummaryResponse(UUID exportId, YearMonth month,
                                           UUID exportedBy, LocalDateTime exportedAt,
                                           int monthlyAverageMinutes,
                                           int rowCount, int excludedCount) {

    static PayrollExportSummaryResponse from(PayrollExport export) {
        return new PayrollExportSummaryResponse(export.id().value(), export.month(),
                export.exportedBy().value(), export.exportedAt().orElse(null),
                export.monthlyAverageMinutes(), export.rowCount(), export.excludedCount());
    }
}
