package com.auditlog.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LayerBoundariesTest {

  private static JavaClasses productionClasses;

  @BeforeAll
  static void importClasses() {
    productionClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.auditlog");
  }

  @Test
  void layeredArchitectureIsRespected() {
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Domain")
        .definedBy("com.auditlog.domain..")
        .layer("Repository")
        .definedBy("com.auditlog.repository..")
        .layer("Service")
        .definedBy("com.auditlog.service..")
        .layer("Api")
        .definedBy("com.auditlog.api..")
        .whereLayer("Api")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Service")
        .mayOnlyBeAccessedByLayers("Api")
        .whereLayer("Repository")
        .mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Domain")
        .mayOnlyBeAccessedByLayers("Api", "Service", "Repository")
        .check(productionClasses);
  }

  @Test
  void domainDependsOnNothingInsideAuditlog() {
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("com.auditlog.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.auditlog.api..", "com.auditlog.service..", "com.auditlog.repository..");

    rule.check(productionClasses);
  }

  @Test
  void repositoryDependsOnlyOnDomain() {
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("com.auditlog.repository..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.auditlog.api..", "com.auditlog.service..");

    rule.check(productionClasses);
  }

  @Test
  void serviceDoesNotDependOnApi() {
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("com.auditlog.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.auditlog.api..");

    rule.check(productionClasses);
  }

  @Test
  void apiDoesNotDependOnRepositoryDirectly() {
    ArchRule rule =
        ArchRuleDefinition.noClasses()
            .that()
            .resideInAPackage("com.auditlog.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.auditlog.repository..");

    rule.check(productionClasses);
  }
}
