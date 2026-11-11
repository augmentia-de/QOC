package de.augmentia.oc.engine.http.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

/**
 * Architecture gates for the quarkus module.
 */
@AnalyzeClasses(packages = "de.augmentia.oc.engine.http",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule noImplSuffix = noClasses()
            .should().haveSimpleNameEndingWith("Impl");

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule javaSqlOnlyInSqlAdapter = noClasses()
            .that().resideOutsideOfPackages("..adapter.sql..", "..adapter.sqlite..")
            .should().accessClassesThat().resideInAPackage("java.sql..");
}
