package com.caseware.fanout.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Layer rules of the hexagonal structure (plan.md, "Dependency rules").
 */
@AnalyzeClasses(packages = "com.caseware.fanout", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String DOMAIN = "com.caseware.fanout.domain..";
    private static final String APPLICATION = "com.caseware.fanout.application..";
    private static final String SERVICE = "com.caseware.fanout.application.service..";
    private static final String ADAPTER = "com.caseware.fanout.adapter..";
    private static final String CONFIG = "com.caseware.fanout.config..";

    @ArchTest
    static final ArchRule domain_depends_only_on_java =
            classes().that().resideInAPackage(DOMAIN)
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN);

    @ArchTest
    static final ArchRule application_depends_only_on_domain_and_java =
            classes().that().resideInAPackage(APPLICATION)
                    .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN, APPLICATION);

    @ArchTest
    static final ArchRule domain_and_application_use_no_framework_or_jdbc =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "lombok..", "org.mapstruct..", "java.sql..", "javax.sql..");

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_services_or_config =
            noClasses().that().resideInAPackage(ADAPTER)
                    .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONFIG);

    @ArchTest
    static final ArchRule adapters_do_not_depend_on_each_other =
            slices().matching("com.caseware.fanout.adapter.(*).(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule ports_and_dtos_do_not_depend_on_services =
            noClasses().that().resideInAnyPackage(DOMAIN, "com.caseware.fanout.application.port..",
                            "com.caseware.fanout.application.dto..")
                    .should().dependOnClassesThat().resideInAPackage(SERVICE);

    @ArchTest
    static final ArchRule only_config_wires_adapters =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                    .should().dependOnClassesThat().resideInAnyPackage(ADAPTER, CONFIG);
}
