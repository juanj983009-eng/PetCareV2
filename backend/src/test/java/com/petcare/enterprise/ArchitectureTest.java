package com.petcare.enterprise;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = ArchitectureTest.BASE_PACKAGE,
        importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE_PACKAGE = "com.petcare.enterprise";

    @ArchTest
    static final ArchRule domain_is_independent_from_frameworks_and_adapters = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "jakarta.servlet..",
                    "jakarta.ws.rs..",
                    "..presentation..",
                    "..infrastructure..")
            .allowEmptyShould(true)
            .because("domain code must remain independent from frameworks and outer adapters");

    @ArchTest
    static final ArchRule application_does_not_depend_on_presentation = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .allowEmptyShould(true)
            .because("application use cases must not depend on transport adapters");

    @ArchTest
    static final ArchRule domain_and_application_do_not_read_security_or_http_context = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.security..",
                    "jakarta.servlet..")
            .allowEmptyShould(true)
            .because("trusted request identity is resolved only by an infrastructure adapter");

    @ArchTest
    static final ArchRule only_authorized_adapter_reads_spring_security_context = noClasses()
            .that().doNotHaveFullyQualifiedName(
                    BASE_PACKAGE + ".identity.infrastructure.security.SpringSecurityTenantContext")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.security.core.context.SecurityContextHolder")
            .allowEmptyShould(true)
            .because("SpringSecurityTenantContext is the single SecurityContext boundary");

    @ArchTest
    static final ArchRule presentation_does_not_resolve_tenant_identity = noClasses()
            .that().resideInAPackage("..presentation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..identity.infrastructure.security..")
            .allowEmptyShould(true)
            .because("presentation delegates tenant resolution to application use cases");

    @ArchTest
    static final ArchRule spring_data_repositories_stay_inside_tenancy_persistence = noClasses()
            .that().resideOutsideOfPackage("..tenancy.infrastructure.persistence..")
            .should().dependOnClassesThat().haveSimpleNameStartingWith("SpringData")
            .allowEmptyShould(true)
            .because("internal JPA repositories must not be consumed outside their adapter package");

    @ArchTest
    static final ArchRule presentation_is_not_used_from_domain = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..presentation..")
            .allowEmptyShould(true)
            .because("domain code must not use presentation code");

    @ArchTest
    static final ArchRule modules_are_free_of_cycles = slices()
            .matching(BASE_PACKAGE + ".(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("module dependencies must remain acyclic");

    @ArchTest
    static final ArchRule tenancy_does_not_access_audit_infrastructure = noClasses()
            .that().resideInAPackage("..tenancy..")
            .should().dependOnClassesThat().resideInAPackage("..audit.infrastructure..")
            .allowEmptyShould(true)
            .because("tenancy must use the explicit audit application interface");

    private ArchitectureTest() {
    }
}
