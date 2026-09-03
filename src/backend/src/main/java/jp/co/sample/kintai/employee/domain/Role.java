package jp.co.sample.kintai.employee.domain;

/**
 * 権限（要件定義書 4 章）。
 *
 * <p>DB の {@code employee_roles_role_check} と一対一で対応する。
 */
public enum Role {

    /** 自分の打刻・勤怠。<strong>全社員が持つ。</strong> */
    EMPLOYEE,

    /** 部下の勤怠の承認。 */
    APPROVER,

    /** 人事。締めの実行と、承認者が得られない場合の代行（BR-11 の 5）。 */
    HR,

    /** システム管理。マスタの保守。 */
    ADMIN
}
