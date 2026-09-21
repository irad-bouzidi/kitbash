package com.example.demo

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * The architecture, as a test your build runs.
 *
 * An architecture that is only a folder layout survives until the first person who needs a
 * repository in a hurry. These rules are what makes "hexagonal" a property of this project rather
 * than a sentence in its README — and they run in about a second, on already-compiled classes,
 * with no context and no database.
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
     * The rule the whole architecture exists for. If the domain may import Spring, every other
     * rule here is decoration.
     */
    @ArchTest
    fun domainIsFrameworkFree(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "com.fasterxml..", "io.swagger..")
            .because(
                "the domain is plain Kotlin: it is the part that outlives the framework it was " +
                    "first written under, and it must be testable without starting one. Put the " +
                    "annotation on an adapter instead.",
            ).check(classes)
    }

    @ArchTest
    fun domainDependsOnNothingInside(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..adapter..", "..config..")
            .because(
                "the domain sits at the centre. Anything it needs from further out is an interface " +
                    "the application declares as a port, which the domain does not call either.",
            ).check(classes)
    }

    /** The dependency-inversion half. Ports point inwards; adapters implement them. */
    @ArchTest
    fun applicationDoesNotKnowItsAdapters(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .because(
                "an application that names an adapter cannot be driven by a second one. Declare a " +
                    "port in application.port.outbound and let the adapter implement it.",
            ).check(classes)
    }

    @ArchTest
    fun applicationDoesNotDependOnPersistence(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
            .because(
                "persistence is one driven adapter among several possible ones. A port that speaks " +
                    "JPA has put the database back in the middle under a new name.",
            ).check(classes)
    }

    /**
     * Adapters are siblings, not a stack. Web calling persistence directly is the most common way
     * a hexagonal project quietly becomes a layered one.
     */
    @ArchTest
    fun adaptersDoNotCallEachOther(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..adapter.inbound..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter.outbound..")
            .because(
                "a driving adapter reaches the outside world through the application's ports, " +
                    "never through another adapter.",
            ).check(classes)
    }
}
