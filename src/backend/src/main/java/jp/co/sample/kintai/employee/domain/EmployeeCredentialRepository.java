package jp.co.sample.kintai.employee.domain;

import java.util.Optional;

import jp.co.sample.kintai.shared.domain.EmployeeId;

/** 認証情報のポート。 */
public interface EmployeeCredentialRepository {

    Optional<EmployeeCredential> find(EmployeeId employeeId);

    /** 登録または更新する。初期発行と再設定で経路を分けない。 */
    void save(EmployeeCredential credential);
}
