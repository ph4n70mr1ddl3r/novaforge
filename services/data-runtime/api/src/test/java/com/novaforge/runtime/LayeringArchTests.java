package com.novaforge.runtime;

import com.novaforge.testsupport.architecture.LayeringRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;

/**
 * The corpus's ArchUnit module rules against the Data Runtime's classpath
 * (PHASE-1 §8): api → engine → storage/authorization, no skips.
 */
@AnalyzeClasses(packages = "com.novaforge", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringArchTests extends LayeringRules {
}
