package jp.co.sample.probe.domain;

import org.springframework.core.io.Resource;

/** AR-01 違反。ドメインがフレームワークの型を持つ。 */
public class UsesSpringFramework {

    private Resource resource;

    public Resource resource() {
        return resource;
    }
}
