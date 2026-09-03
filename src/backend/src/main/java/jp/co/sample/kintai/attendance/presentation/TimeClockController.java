package jp.co.sample.kintai.attendance.presentation;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sample.kintai.attendance.application.TimeClockService;
import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 打刻の API。
 *
 * <p><strong>訂正はこのコンテキストが受け付けない。</strong>
 * 訂正は申請と承認を伴うので {@code approval} が受け付ける（BR-09）。
 * 承認された結果として、取消行と新しい打刻行がここへ追記される。
 *
 * <p>この層に業務判断を置かない。現在時刻の解決も
 * {@code application} 層が {@code Clock} から行う（AR-09）。
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/time-clocks")
public class TimeClockController {

    private final TimeClockService timeClocks;

    public TimeClockController(TimeClockService timeClocks) {
        this.timeClocks = timeClocks;
    }

    /**
     * 打刻する。
     *
     * <p>{@code 201} を返す。打刻は<strong>追記</strong>であり、
     * 同じ内容を 2 回送れば 2 件記録されるので冪等ではない。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PunchResponse punch(@PathVariable UUID employeeId,
                               @Valid @RequestBody PunchRequest request) {
        return PunchResponse.from(timeClocks.punch(new EmployeeId(employeeId),
                request.type(), request.occurredAtOrNow()));
    }
}
