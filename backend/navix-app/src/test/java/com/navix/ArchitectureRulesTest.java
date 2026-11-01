package com.navix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.navix.loan.service.LoanMath;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture gates (PLAN Phase 1 step 1, Phase 12), over the main classes of every module. Only
 * rules the code base already satisfies; each one fails the build if it is broken later. ("Controllers
 * never touch repositories" does not hold in navix today, 15 call sites, so it is not here.)
 */
@AnalyzeClasses(packages = "com.navix", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    /**
     * Rates are read from the frozen {@code pricing_config} snapshot. The {@link LoanMath} rate
     * constants are only its seed defaults, so nothing else may read them. (The int constants, such
     * as the penal cap, are inlined by javac and not visible here.)
     */
    @ArchTest
    static final ArchRule pricingRatesAreOnlySeedDefaults = noClasses()
            .that().doNotHaveFullyQualifiedName(LoanMath.class.getName())
            .should().accessField(LoanMath.class, "PROCESSING_FEE_RATE")
            .orShould().accessField(LoanMath.class, "GST_RATE")
            .orShould().accessField(LoanMath.class, "DAILY_INTEREST_RATE")
            .orShould().accessField(LoanMath.class, "LATE_PENALTY_RATE")
            .because("money figures come from the pricing version frozen on the offer (PricingService)");

    /** Money math lives in navix-loan; other modules ask it through ports such as LoanDirectory. */
    @ArchTest
    static final ArchRule moneyMathOnlyInTheLoanModule = noClasses()
            .that().resideOutsideOfPackage("com.navix.loan..")
            .should().dependOnClassesThat().haveFullyQualifiedName(LoanMath.class.getName());

    /** The modules (com.navix.loan, com.navix.collections, ...) form no package cycle. */
    @ArchTest
    static final ArchRule noModuleCycles = slices().matching("com.navix.(*)..").should().beFreeOfCycles();
}
