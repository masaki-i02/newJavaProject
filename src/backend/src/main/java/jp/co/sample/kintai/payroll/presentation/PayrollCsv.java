package jp.co.sample.kintai.payroll.presentation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;

import jp.co.sample.kintai.payroll.domain.PayrollRow;

/**
 * 給与連携の CSV（BR-18）。
 *
 * <p><strong>組み立ては {@code presentation} の責務である。</strong>
 * {@code application} は {@link PayrollRow} のリストを返すところまでを担う。
 *
 * <table>
 *   <caption>形式</caption>
 *   <tr><th>項目</th><th>決定</th><th>理由</th></tr>
 *   <tr><td>文字コード</td><td>UTF-8（BOM 付き）</td>
 *       <td>BOM が無いと Excel が Shift_JIS と解釈して見出しが化ける</td></tr>
 *   <tr><td>改行</td><td>CRLF</td><td>RFC 4180。給与ソフトの取込で最も通る</td></tr>
 *   <tr><td>時間</td><td>分（整数）</td>
 *       <td>1 分単位で丸めない（BR-01）。{@code H:MM} にすると受け手が分へ戻すときに丸めが混入する</td></tr>
 *   <tr><td>清算期間の終了日</td><td><strong>閉区間の最終日</strong></td>
 *       <td>CSV は人が読む。半開区間の上限を「月の終わり」として渡すと必ず取り違える</td></tr>
 * </table>
 */
final class PayrollCsv {

    /** Excel が UTF-8 と判別するための BOM。 */
    private static final String BOM = "﻿";

    /** RFC 4180。 */
    private static final String CRLF = "\r\n";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");

    private static final List<String> HEADER = List.of(
            "社員番号", "対象月", "清算期間開始", "清算期間終了", "労働時間制度",
            "所定労働日数", "出勤日数", "年休日数", "欠勤日数",
            "実労働", "所定内", "所定超",
            "法定外残業60hまで", "法定外残業60h超", "法定休日", "深夜",
            "所定総", "不足");

    private PayrollCsv() {
    }

    static byte[] render(List<PayrollRow> rows) {
        StringBuilder csv = new StringBuilder(BOM);
        csv.append(String.join(",", HEADER)).append(CRLF);
        for (PayrollRow row : rows) {
            csv.append(line(row)).append(CRLF);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String line(PayrollRow row) {
        return String.join(",",
                row.employeeNumber().value(),
                row.month().format(MONTH),
                row.period().from().toString(),
                // ★ 半開区間の上限ではなく、閉区間の最終日を書く（落とし穴 10・112）
                row.period().toExclusive().minusDays(1).toString(),
                row.system().name(),
                String.valueOf(row.scheduledDays()),
                String.valueOf(row.attendedDays()),
                String.valueOf(row.paidLeaveDays()),
                String.valueOf(row.absentDays()),
                minutes(row.workingTime()),
                minutes(row.scheduledInsideTime()),
                minutes(row.beyondScheduledTime()),
                minutes(row.overtimeUpTo60Time()),
                minutes(row.overtimeOver60Time()),
                minutes(row.legalHolidayTime()),
                minutes(row.nightTime()),
                minutes(row.scheduledTotalTime()),
                minutes(row.shortageTime()));
    }

    private static String minutes(Duration value) {
        return String.valueOf(value.toMinutes());
    }
}
