package de.augmentia.oc.engine.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

/**
 * Mechanical architecture gates G-1…G-9 from qualitaet/02-architecture-pipeline.
 * Only production classes are inspected; fakes and tests are excluded.
 */
@AnalyzeClasses(packages = "de.augmentia.oc.engine",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule s0DoesNotDependOnLaterStages = noClasses()
            .that().resideInAnyPackage("..core.model..", "..core.error..", "..core.json..",
                    "..core.pattern..", "..core.shell.scan..", "..core.port..")
            .should().dependOnClassesThat().resideInAnyPackage("..core.contract..", "..core.event..",
                    "..core.eval..", "de.augmentia.oc.engine.runtime..", "..adapter..");

    @ArchTest
    static final ArchRule s1DoesNotDependOnLaterStages = noClasses()
            .that().resideInAnyPackage("..core.contract..", "..core.event..")
            .should().dependOnClassesThat().resideInAnyPackage("..core.eval..",
                    "de.augmentia.oc.engine.runtime..", "..adapter..");

    @ArchTest
    static final ArchRule s2DoesNotDependOnLaterStages = noClasses()
            .that().resideInAnyPackage("..core.eval..")
            .should().dependOnClassesThat().resideInAnyPackage("de.augmentia.oc.engine.runtime..",
                    "..adapter..");

    /**
     * Wiring may depend on core contracts/ports, but never on runtime or adapters.
     * This closes the gap left by S0–S2, which do not include {@code core.wiring}.
     */
    @ArchTest
    static final ArchRule wiringDoesNotDependOnRuntimeOrAdapter = noClasses()
            .that().resideInAPackage("..core.wiring..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "de.augmentia.oc.engine.runtime..", "..adapter..");

    @ArchTest
    static final ArchRule runtimeDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..runtime..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule adaptersDoNotDependOnRuntime = noClasses()
            .that().resideInAPackage("..adapter..")
            .should().dependOnClassesThat().resideInAPackage("de.augmentia.oc.engine.runtime..");

    /**
     * Provider clients (including the decision adapter) are leaf adapters:
     * they may use core contracts/ports and JDK HTTP, but not engine wiring or
     * higher runtime layers.
     */
    @ArchTest
    static final ArchRule providerClientsAreLeafAdapters = noClasses()
            .that().resideInAPackage("..runtime.provider.client..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.wiring..", "..runtime.execution..", "..runtime.session..",
                    "..runtime.workflow..", "..runtime.tool..", "..adapter..");

    @ArchTest
    static final ArchRule adaptersAreOnlyUsedByWiring = noClasses()
            .that().resideOutsideOfPackage("..adapter..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule noCyclesBetweenPackages = slices()
            .matching("de.augmentia.oc.engine.(*.*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule javaSqlOnlyInSqlAdapter = noClasses()
            .that().resideOutsideOfPackages("..adapter.sql..", "..adapter.sqlite..")
            .should().accessClassesThat().resideInAPackage("java.sql..");

    @ArchTest
    static final ArchRule engineStaysFreeOfQuarkusAndHttp = noClasses()
            .that().resideInAPackage("de.augmentia.oc.engine..")
            .and().resideOutsideOfPackage("de.augmentia.oc.engine.http..")
            .should().dependOnClassesThat().resideInAnyPackage("io.quarkus..", "jakarta..",
                    "javax.ws.rs..", "org.jboss..", "com.fasterxml..", "io.smallrye..",
                    "org.eclipse.microprofile..", "de.augmentia.qoc.workflowrunner..",
                    "de.augmentia.oc.engine.http..");

    @ArchTest
    static final ArchRule noImplSuffix = noClasses()
            .should().haveSimpleNameEndingWith("Impl");

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    /** Top-level port types are interfaces; nested records are fine. */
    @ArchTest
    static final ArchRule portsAreInterfaces = noClasses()
            .that().resideInAPackage("..core.port..")
            .and().haveNameNotMatching(".*\\$.*")
            .should().notBeInterfaces();
}
