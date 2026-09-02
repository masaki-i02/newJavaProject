package jp.co.sample.kintai.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import jp.co.sample.kintai.attendance.domain.TimeClockEvent;
import jp.co.sample.kintai.attendance.domain.TimeClockSequence;

/** 打刻列を組み立てる。時刻は {@code "13:00"} か {@code "2026-04-08T03:00"} で書ける。 */
public final class Punches {

    private final LocalDate baseDate;
    private final List<TimeClockEvent> events = new ArrayList<>();

    private Punches(LocalDate baseDate) {
        this.baseDate = baseDate;
    }

    public static Punches on(String date) {
        return new Punches(LocalDate.parse(date));
    }

    public Punches in(String at) {
        events.add(new TimeClockEvent.ClockIn(parse(at)));
        return this;
    }

    public Punches out(String at) {
        events.add(new TimeClockEvent.ClockOut(parse(at)));
        return this;
    }

    public Punches breakFrom(String at) {
        events.add(new TimeClockEvent.BreakStart(parse(at)));
        return this;
    }

    public Punches breakTo(String at) {
        events.add(new TimeClockEvent.BreakEnd(parse(at)));
        return this;
    }

    public TimeClockSequence build() {
        return TimeClockSequence.of(events);
    }

    private LocalDateTime parse(String at) {
        return at.contains("T") ? LocalDateTime.parse(at) : baseDate.atTime(LocalTime.parse(at));
    }
}
