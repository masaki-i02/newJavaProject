package jp.co.sample.probe.domain;

import org.hibernate.annotations.Immutable;

/** AR-01 違反。ドメインが Hibernate の拡張に依存している。 */
@Immutable
public class UsesHibernate {

    public String describe() {
        return "probe";
    }
}
