package com.novaforge.testsupport.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit module rules (PHASE-1 §8/§10), shared through test-support: services extend
 * this suite so the rules run against each service's classpath — the concrete test
 * class carries @AnalyzeClasses (annotations are not inherited). The data-runtime
 * layering is api → engine → storage/authorization with no skips; platform libs carry
 * no Spring web; only the storage module touches tenant SQL.
 */
public abstract class LayeringRules {

    @ArchTest
    static final ArchRule apiMustNotTouchStorage =
            noClasses().that().resideInAPackage("com.novaforge.runtime.api..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.novaforge.runtime.storage..")
                    .because("api → engine only; storage is reached through the engine (no layer skips)");

    @ArchTest
    static final ArchRule apiMustNotTouchAuthorization =
            noClasses().that().resideInAPackage("com.novaforge.runtime.api..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.novaforge.runtime.authorization..")
                    .because("authorization decisions belong to the engine pipeline; the api layer delegates");

    @ArchTest
    static final ArchRule engineMustNotTouchServlets =
            noClasses().that().resideInAPackage("com.novaforge.runtime.engine..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..")
                    .because("the engine stays transport-agnostic (the Phase 3 event spine binds it further)");

    @ArchTest
    static final ArchRule platformLibsCarryNoSpringWeb =
            noClasses().that().resideInAnyPackage("com.novaforge.common..", "com.novaforge.metadata..",
                            "com.novaforge.security..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
                    .because("common-core's zero-web charter (PHASE-0 §5.1) extends to the lib family");

    @ArchTest
    static final ArchRule onlyStorageTouchesTenantSql =
            noClasses().that().resideOutsideOfPackage("com.novaforge.runtime.storage..")
                    .and().resideOutsideOfPackage("com.novaforge.runtime.authorization..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.jdbc..")
                    .because("tenant-record SQL lives only in the storage SPI; the authorization "
                            + "module's platform store is cross-tenant platform data by design "
                            + "(PHASE-1 §6), not tenant records");
}
