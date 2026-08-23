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

    private ArchitectureTest() {
    }
}
