package jp.co.sample.kintai.employee.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/**
 * 組織構造の<strong>事実</strong>に答えるドメインサービス。
 *
 * <p>「ある日付において、誰がどの部署に所属し、その部署の長は誰か」だけを答える。
 *
 * <p><strong>「誰が承認者か」は判断しない。</strong>
 * 自己承認の禁止、退職者のスキップ、人事へのエスカレーション（BR-11 の 4 と 5）は
 * <em>承認の業務ルール</em>であり、{@code approval} の {@code ApproverPolicy} が担う
 * （アーキテクチャ設計書 6.4）。この分割により、
 * 組織の問い合わせを使うたびに承認のルールが付いてくることを避ける。
 *
 * <p>{@code ApproverPolicy} が引くのは<strong>この 5 つだけ</strong>である。
 * {@code AssignmentRepository} などを直接使わせると、BR-11 の実装が 2 か所に散る。
 */
public interface OrganizationChart {

    /** 指定日に社員が所属していた部署。所属が無ければ空。 */
    Optional<Department> departmentOf(EmployeeId employeeId, LocalDate date);

    /** 部署とその祖先を、自分自身から根へ向かう順で返す。 */
    List<Department> selfAndAncestorsOf(DepartmentId departmentId);

    /** 指定日にその部署の長を務めていた社員。長が未設定なら空。 */
    Optional<Managership> managerOf(DepartmentId departmentId, LocalDate date);

    /**
     * その月の途中で所属が始まっていれば、その開始日。
     *
     * <p>BR-11 の 1 の基準日に使う。
     * <strong>この例外が無いと、月中入社の社員は初月の承認者が導出できず、
     * 勤怠を永久に締められない。</strong>
     */
    Optional<LocalDate> assignmentStartWithin(EmployeeId employeeId, YearMonth month);

    /**
     * 指定日にその社員が在籍していたか。
     *
     * <p>BR-11 の 4 は、この判定を<strong>基準日ではなく承認実行時点</strong>で行う。
     * 基準日時点では在籍していた部署長が承認時点で退職していると、
     * その承認を誰も実行できなくなるため。
     */
    boolean isActiveOn(EmployeeId employeeId, LocalDate date);
}
