package jp.co.sample.kintai.attendance.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sample.kintai.attendance.application.AttendanceQueryService;
import jp.co.sample.kintai.attendance.application.TimeClockService;
import jp.co.sample.kintai.shared.domain.EmployeeId;
import jp.co.sample.kintai.shared.presentation.AuthenticatedEmployee;

/**
 * 打刻の API。
 *
 * <p><strong>訂正はこのコンテキストが受け付けない。</strong>
 * 訂正は申請と承認を伴うので {@code approval} が受け付ける（BR-09）。
 * 承認された結果として、取消行と新しい打刻行がここへ追記される。
 *
 * <p><strong>打刻できるのは本人だけである</strong>（要件定義書 4 章）。
 * 判定は {@code application} 層が行う。ここでパスの社員 ID と認証された利用者を
 * 突き合わせると、業務判断がコントローラに漏れる。
 *
 * <p>この層に業務判断を置かない。現在時刻の解決も
 * {@code application} 層が {@code Clock} から行う（AR-09）。
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/time-clocks")
public class TimeClockController {

    private final TimeClockService timeClocks;
    private final AttendanceQueryService attendances;

    public TimeClockController(TimeClockService timeClocks,
                               AttendanceQueryService attendances) {
        this.timeClocks = timeClocks;
        this.attendances = attendances;
    }

    /**
     * 打刻する。
     *
     * <p>{@code 201} を返す。打刻は<strong>追記</strong>であり、
     * 同じ内容を 2 回送れば 2 件記録されるので冪等ではない。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PunchResponse punch(@AuthenticationPrincipal AuthenticatedEmployee principal,
                               @PathVariable UUID employeeId,
                               @Valid @RequestBody PunchRequest request) {
        return PunchResponse.from(timeClocks.punch(principal.toRequester(),
                new EmployeeId(employeeId), request.type(), request.occurredAtOrNow()));
    }

    /**
     * その勤務日の打刻を識別子つきで返す（[03 API設計書 2.3]）。
     *
     * <p><strong>訂正申請の画面が取消の対象を選ぶために要る。</strong>
     * 識別子を返す経路が無いと、利用者は実在しない識別子を送るしかなく、
     * 外部キー違反という説明できないエラーになる（落とし穴 66）。
     */
    @GetMapping
    public List<RecordedPunchResponse> list(
            @AuthenticationPrincipal AuthenticatedEmployee principal,
            @PathVariable UUID employeeId, @RequestParam LocalDate workDate) {
        return attendances.recordedOn(principal.toRequester(),
                        new EmployeeId(employeeId), workDate).stream()
                .map(RecordedPunchResponse::from)
                .toList();
    }
}
