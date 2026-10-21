package org.example.ohara;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(OharaApplication.class);

    @Test
    void modulesOnlyDependOnEachOthersExposedTypes() {
        modules.verify();
    }
}
