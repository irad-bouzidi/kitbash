package com.example.demo

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures

/**
 * The architecture, as a test your build runs.
 *
 * Layered is the architecture people assume they have and most often do not: the layers are
 * folders, nothing stops a repository from importing a controller, and two years later the call
 * graph is a ball of string that still has four tidy package names. These rules are the
 * difference, and they run in about a second on already-compiled classes.
 *
 * Each rule is a *function* rather than the `val` of type `ArchRule` that ArchUnit's own examples
 * use, and that is not style. Maven Surefire filters discovered tests by their source, keeps
 * `MethodSource` and drops `FieldSource` — so the field form reports "Tests run: 0" and passes,
 * silently, on a Maven build. Gradle runs both. Since this project can be generated with either
 * build tool, only the form that works under both is safe to ship.
 */
@AnalyzeClasses(packages = ["com.example.demo"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    /**
     * Calls go one way: web to service, service to persistence, and never back up.
     *
     * `consideringOnlyDependenciesInLayers` keeps the rule about this project. Everything here
     * depends on Spring and on the JDK, and a rule with opinions about those would fail for
     * reasons that teach nothing.
     */
    @ArchTest
    fun layersAreRespected(classes: JavaClasses) {
        Architectures
            .layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Web")
            .definedBy("..controller..")
            .layer("Service")
            .definedBy("..service..")
            .layer("Persistence")
            .definedBy("..repository..")
            .layer("Domain")
            .definedBy("..domain..")
            .whereLayer("Web")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Persistence")
            .mayOnlyBeAccessedByLayers("Service")
            .because(
                "a layer may call the one below it. A repository that imports a controller, or a " +
                    "service reached straight from persistence, is a cycle that no package name " +
                    "will make visible later.",
            ).check(classes)
    }

    /** The layer everything else depends on has to be the one that depends on nothing. */
    @ArchTest
    fun domainIsTheBottom(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..controller..", "..service..", "..repository..", "..config..")
            .because(
                "the entity is what the other layers are about. If it reaches back into them, " +
                    "every layer depends on every layer.",
            ).check(classes)
    }

    /** The rule that stops the web layer becoming the application. */
    @ArchTest
    fun webDoesNotTouchPersistence(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..", "org.springframework.data..")
            .because(
                "a controller that queries directly has put a transaction boundary and a business " +
                    "rule in the one class that also parses HTTP.",
            ).check(classes)
    }
}
