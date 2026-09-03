package jp.co.sample.kintai.workrule.infrastructure;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 会社カレンダーの永続化。暦日そのものが主キー。 */
@Entity
@Table(name = "company_calendars")
class CompanyCalendarEntity {

    @Id
    @Column(name = "calendar_date")
    private LocalDate calendarDate;

    @Column(name = "day_type", nullable = false)
    private String dayType;

    @Column(name = "name")
    private String name;

    @Version
    private long version;

    protected CompanyCalendarEntity() {
    }

    CompanyCalendarEntity(LocalDate calendarDate) {
        this.calendarDate = calendarDate;
    }

    LocalDate getCalendarDate() {
        return calendarDate;
    }

    String getDayType() {
        return dayType;
    }

    void setDayType(String dayType) {
        this.dayType = dayType;
    }

    String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }
}
