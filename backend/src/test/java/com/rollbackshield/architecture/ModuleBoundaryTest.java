package com.rollbackshield.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces §18/§19 by build, not just by convention: domain code must not
 * import Spring MVC, AWS SDK, or DynamoDB-specific classes, and application
 * services should not reach into another module's adapter package directly.
 * If this test fails, the fix is almost always "add a port method" or
 * "add a mapping in the api/application layer", not "loosen the rule".
 */
class ModuleBoundaryTest {

    private static com.tngtech.archunit.core.domain.JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rollbackshield");
    }

    @Test
    void domainClassesMustNotDependOnSpring() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.servlet..");
        rule.check(classes);
    }

    @Test
    void domainClassesMustNotDependOnAwsSdk() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk..");
        rule.check(classes);
    }

    @Test
    void domainClassesMustNotDependOnJackson() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..");
        rule.check(classes);
    }

    @Test
    void applicationClassesMustNotDependOnOtherModulesAdapterPackages() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @Test
    void controllersMustNotBeNamedServiceOrRepository() {
        ArchRule rule = classes()
            .that().resideInAPackage("..api..")
            .and().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().haveSimpleNameEndingWith("Controller");
        rule.check(classes);
    }
}
