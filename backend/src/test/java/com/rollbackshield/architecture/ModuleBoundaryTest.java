package com.rollbackshield.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the module boundaries and the vendor-SDK boundary by build, not
 * by convention. The fix for a failure here is almost always "move the
 * provider call into connectors/<provider>/adapter", "introduce a port
 * method", or "map in the api/application layer" -- not loosening the rule.
 */
class ModuleBoundaryTest {

    /** Provider SDKs that must stay outside the core domain and application layers. */
    private static final String[] VENDOR_SDKS = {
        "software.amazon.awssdk..",
        "io.fabric8..",
        "org.postgresql.."
    };

    private static com.tngtech.archunit.core.domain.JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rollbackshield");
    }

    @Test
    void domainClassesMustNotDependOnFrameworksOrVendorSdks() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.servlet..", "com.fasterxml.jackson..",
                "software.amazon.awssdk..", "io.fabric8..", "org.postgresql..");
        rule.check(classes);
    }

    @Test
    void integrationAndReversibilityDomainsMustNotImportVendorSdks() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage(
                "com.rollbackshield.integrations..domain..",
                "com.rollbackshield.reversibility..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(VENDOR_SDKS);
        rule.check(classes);
    }

    @Test
    void applicationClassesMustNotDependOnVendorSdks() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(VENDOR_SDKS);
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
    void corePackagesMustNotDependOnConnectorImplementations() {
        // Adding Kubernetes (or any provider) must not leak into the core:
        // everything outside connectors/ talks to it only through domain ports.
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.rollbackshield.connectors..")
            .should().dependOnClassesThat().resideInAPackage("com.rollbackshield.connectors..");
        rule.check(classes);
    }

    @Test
    void vendorSdkTypesAreConfinedToAdapterPackages() {
        ArchRule rule = classes()
            .should(new ArchCondition<com.tngtech.archunit.core.domain.JavaClass>(
                "depend on vendor SDKs only from adapter or shared wiring packages") {
                @Override
                public void check(com.tngtech.archunit.core.domain.JavaClass item,
                                  ConditionEvents events) {
                    boolean usesVendorSdk = item.getDirectDependenciesFromSelf().stream()
                        .map(dependency -> dependency.getTargetClass().getPackageName())
                        .anyMatch(ModuleBoundaryTest::isVendorSdkPackage);
                    if (!usesVendorSdk) {
                        return;
                    }
                    String packageName = item.getPackageName();
                    boolean allowed = packageName.contains(".adapter")
                        || packageName.startsWith("com.rollbackshield.shared.dynamodb")
                        || packageName.startsWith("com.rollbackshield.shared.sqs")
                        || packageName.startsWith("com.rollbackshield.shared.events.config");
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(item, item.getName()
                            + " depends on a vendor SDK but does not reside in an adapter package"));
                    }
                }
            });
        rule.check(classes);
    }

    private static boolean isVendorSdkPackage(String packageName) {
        return packageName.startsWith("software.amazon.awssdk")
            || packageName.startsWith("io.fabric8")
            || packageName.startsWith("org.postgresql");
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
