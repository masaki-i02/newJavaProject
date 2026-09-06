package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sample.kintai.attendance.application.AttendanceQueryService;
import jp.co.sample.kintai.attendance.application.TimeClockService;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/** 日次勤怠の API。 */
@RestController
@RequestMapping("/api/employees/{employeeId}/attendances")
public class AttendanceController {

    private final AttendanceQueryService attendances;
    private final TimeClockService timeClocks;

    public AttendanceController(AttendanceQueryService attendances,
                                TimeClockService timeClocks) {
        this.attendances = attendances;
        this.timeClocks = timeClocks;
    }

    /**
     * 期間の日次勤怠一覧（[03 API設計書 3.1]）。
     *
     * <p>計算済みの日だけが返る。打刻が無い日・未退勤の日は含まれない。
     *
     * <p><strong>期間は半開区間で受ける。</strong>
     * 月中入社の初月は「入社日から翌月 1 日まで」であり、暦月に固定できない。
     *
     * <p><strong>合計は分の合計だけを返す。</strong>
     * 目的は「排他区分の合計＝実労働時間」を
     * <strong>受け取った側でも検算できる</strong>ことである（3.1）。
     * 日数（出勤日数・所定労働日数）は含めない。
     * 年休の日と法定休日の出勤で数え方が変わるので、
     * ここで行を数えると<strong>月次清算と違う定義の同名の数</strong>が生まれる
     * （CLAUDE.md 落とし穴 67）。日数を持つのは月次清算である。
     */
    @GetMapping
    public AttendanceListResponse list(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId,
            @RequestParam LocalDate from, @RequestParam LocalDate toExclusive) {
        List<DailyAttendanceResponse> days = attendances.findByPeriod(principal.toRequester(),
                        new EmployeeId(employeeId), from, toExclusive).stream()
                .map(DailyAttendanceResponse::from).toList();
        return new AttendanceListResponse(from, toExclusive, days, Totals.of(days));
    }

    /**
     * 期間の日次勤怠。
     *
     * <p><strong>要求した期間を応答に載せる。</strong>
     * 配列だけを返すと、受け取った側は「空なのは勤怠が無いからか、
     * 期間を取り違えたからか」を区別できない。
     */
    public record AttendanceListResponse(LocalDate from, LocalDate toExclusive,
                                         List<DailyAttendanceResponse> days,
                                         Totals totals) {
    }

    /**
     * 期間の合計（分）。
     *
     * <p><strong>{@code baseMinutes + overtimeWithinStatutoryMinutes
     * + overtimeBeyondStatutoryMinutes + legalHolidayMinutes = workingMinutes}</strong>
     * が成り立つ。深夜は排他区分ではなく<strong>上乗せ</strong>なので、
     * この式に入らない（CLAUDE.md 落とし穴 5）。
     */
    public record Totals(int workingMinutes, int breakMinutes, int baseMinutes,
                         int overtimeWithinStatutoryMinutes,
                         int overtimeBeyondStatutoryMinutes,
                         int nightMinutes, int legalHolidayMinutes) {

        static Totals of(List<DailyAttendanceResponse> days) {
            return new Totals(
                    sum(days, DailyAttendanceResponse::workingMinutes),
                    sum(days, DailyAttendanceResponse::breakMinutes),
                    sum(days, DailyAttendanceResponse::baseMinutes),
                    sum(days, DailyAttendanceResponse::overtimeWithinStatutoryMinutes),
                    sum(days, DailyAttendanceResponse::overtimeBeyondStatutoryMinutes),
                    sum(days, DailyAttendanceResponse::nightMinutes),
                    sum(days, DailyAttendanceResponse::legalHolidayMinutes));
        }

        private static int sum(List<DailyAttendanceResponse> days,
                               java.util.function.ToIntFunction<DailyAttendanceResponse> of) {
            return days.stream().mapToInt(of).sum();
        }
    }

    /**
     * 現在の勤務状態（[03 API設計書 2.2]）。
     *
     * <p><strong>{@code /{workDate}} より前に置く。</strong>
     * あとに置くと {@code current} が {@code LocalDate} として解釈され、
     * 400 になる。
     */
    @GetMapping("/current")
    public CurrentAttendanceResponse current(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId) {
        return CurrentAttendanceResponse.from(
                timeClocks.current(principal.toRequester(), new EmployeeId(employeeId)));
    }

    /** 指定日の日次勤怠（内訳つき）。 */
    @GetMapping("/{workDate}")
    public DailyAttendanceResponse get(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @PathVariable LocalDate workDate) {
        return attendances.find(principal.toRequester(), new EmployeeId(employeeId), workDate)
                .map(DailyAttendanceResponse::from)
                .orElseThrow(() -> new AttendanceNotFoundException(workDate));
    }

    /**
     * 日次勤怠が無い。
     *
     * <p>打刻が無い日・未退勤の日は計算されないので、これは<strong>正常に起こりうる。</strong>
     * 例外の型で 404 に落ちるようにしておき、コントローラでステータスを組み立てない。
     */
    static final class AttendanceNotFoundException extends DomainException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        AttendanceNotFoundException(LocalDate workDate) {
            super("その勤務日の日次勤怠はまだ計算されていません: " + workDate);
        }

        @Override
        public String errorCode() {
            return "urn:kintai:error:resource-not-found";
        }

        @Override
        public DomainErrorKind kind() {
            return DomainErrorKind.NOT_FOUND;
        }

        @Override
        public String title() {
            return "日次勤怠が見つかりません";
        }
    }
}
