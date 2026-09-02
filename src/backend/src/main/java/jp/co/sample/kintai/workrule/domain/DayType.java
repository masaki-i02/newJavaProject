package jp.co.sample.kintai.workrule.domain;

/** 暦日の区分（BR-07）。 */
public enum DayType {

    /** 所定労働日。 */
    WORKDAY,

    /** 法定休日。全時間が 35% 割増になり、時間外労働の判定対象から外れる。 */
    LEGAL_HOLIDAY,

    /** 所定休日。所定労働時間 0 として扱う。8 時間までは法定内残業。 */
    NON_LEGAL_HOLIDAY
}
