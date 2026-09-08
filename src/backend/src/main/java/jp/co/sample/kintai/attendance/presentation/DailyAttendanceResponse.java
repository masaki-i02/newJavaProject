package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

import jp.co.sample.kintai.attendance.domain.BreakTimeRequirement;
import jp.co.sample.kintai.attendance.domain.DailyAttendance;
import jp.co.sample.kintai.attendance.domain.WorkSlice;
import jp.co.sample.kintai.shared.domain.PremiumType;

/**
 * 日次勤怠の応答。
 *
 * <p><strong>時間は分単位の整数で返す。</strong>
 * ISO-8601 の {@code PT8H30M} は画面での加工が面倒で、
 * 小数の「時間」は丸め誤差を生む。労働時間は 1 分単位なので（BR-01）、
 * 分の整数は情報を落とさない。
 *
 * <p><strong>他コンテキストが所有する概念を混ぜない</strong>（設計規約チェックリスト 3）。
 * 社員番号・氏名・部署は {@code employee} から引く。ここには載せない。
 *
 * <p>日時にオフセットを含めない。打刻や始業時刻は
 * 「会社の壁掛け時計が何時を指していたか」であって、
 * クライアントのタイムゾーンで解釈されるべきものではない（API 共通仕様 1.1）。
 */
public record DailyAttendanceResponse(
        LocalDate workDate,
        String dayType,
        String workingTimeSystem,
        int workingMinutes,
        int breakMinutes,
        int baseMinutes,
        int overtimeWithinStatutoryMinutes,
        int overtimeBeyondStatutoryMinutes,
        int nightMinutes,
        int legalHolidayMinutes,
        boolean breakRequirementSatisfied,
        /**
         * 楽観ロックの版。<strong>1 件を返すときだけ入れる。</strong>
         *
         * <p>再計算（{@code POST .../recalculation}）は版を必須にしているのに、
         * <strong>版を取得する経路が無かった。</strong>
         * 送るべき値を知る手段が無いまま必須にしても、守られているのは
         * 「誰も呼べない」ことだけである（05 API設計書 1.1）。
         *
         * <p>一覧には入れない（決定表「一覧に版を載せるか」）。
         * 再計算するのは開いた 1 日だけである。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long version,
        List<SliceResponse> slices) {

    /**
     * 内訳の 1 区間。
     *
     * <p><strong>{@code calendarDate} を持たせる。</strong>
     * 法定休日労働は暦日で判断するので（BR-07）、
     * どの暦日の分かをデータで説明できるようにする。
     */
    public record SliceResponse(LocalDate calendarDate, LocalDateTime startedAt,
                                LocalDateTime endedAt, int minutes,
                                List<String> premiums) {
    }

    /** 一覧の行。<strong>版は入れない。</strong> */
    public static DailyAttendanceResponse from(DailyAttendance attendance) {
        return from(attendance, null);
    }

    /** 1 件。<strong>版を入れる</strong>（再計算がこれを必須にしている）。 */
    public static DailyAttendanceResponse from(DailyAttendance attendance, Long version) {
        return new DailyAttendanceResponse(
                attendance.workDate(),
                attendance.dayType().name(),
                attendance.workingTimeSystem().name(),
                minutes(attendance.workingTime()),
                minutes(attendance.breakTime()),
                minutes(attendance.baseTime()),
                minutes(attendance.overtimeWithinStatutoryTime()),
                minutes(attendance.overtimeBeyondStatutoryTime()),
                minutes(attendance.nightTime()),
                minutes(attendance.legalHolidayTime()),
                new BreakTimeRequirement(attendance.workingTime(), attendance.breakTime())
                        .isSatisfied(),
                version,
                attendance.slices().stream().map(DailyAttendanceResponse::toSlice).toList());
    }

    private static SliceResponse toSlice(WorkSlice slice) {
        return new SliceResponse(
                slice.calendarDate(),
                slice.range().start(),
                slice.range().end(),
                minutes(slice.duration()),
                slice.premiums().stream().map(PremiumType::name).sorted().toList());
    }

    private static int minutes(java.time.Duration duration) {
        return Math.toIntExact(duration.toMinutes());
    }
}
