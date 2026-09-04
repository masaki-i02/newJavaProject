package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sample.kintai.attendance.application.AttendanceQueryService;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.domain.DomainErrorKind;
import jp.co.sample.kintai.shared.domain.DomainException;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/** 日次勤怠の API。 */
@RestController
@RequestMapping("/api/employees/{employeeId}/attendances")
public class AttendanceController {

    private final AttendanceQueryService attendances;

    public AttendanceController(AttendanceQueryService attendances) {
        this.attendances = attendances;
    }

    /**
     * その月の日次勤怠一覧。
     *
     * <p>計算済みの日だけが返る。打刻が無い日・未退勤の日は含まれない。
     */
    @GetMapping
    public List<DailyAttendanceResponse> list(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @RequestParam YearMonth month) {
        return attendances.findByMonth(principal.toRequester(),
                        new EmployeeId(employeeId), month).stream()
                .map(DailyAttendanceResponse::from).toList();
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
