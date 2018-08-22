package net.corda.sandbox.analysis

import net.corda.sandbox.messages.Severity
import net.corda.sandbox.references.ClassModule
import net.corda.sandbox.references.MemberModule
import java.nio.file.Path

/**
 * The configuration to use for an analysis.
 *
 * @property whitelist The whitelist of class names.
 * @property pinnedClasses Classes and packages to leave untouched (in addition to the whitelist).
 * @property sandboxPrefix The package name prefix to use for classes loaded into a sandbox.
 * @property classResolver Functionality used to resolve the qualified name and relevant information about a class.
 * @property minimumSeverityLevel The minimum severity level to log and report.
 * @property classPath The extended class path to use for the analysis.
 * @property analyzePinnedClasses Analyze pinned classes unless covered by the provided whitelist.
 * @property analyzeAnnotations Analyze annotations despite not being explicitly referenced.
 * @property prefixFilters Only record messages where the originating class name matches one of the provided prefixes.
 * If none are provided, all messages will be reported.
 * @property classModule Module for handling evolution of a class hierarchy during analysis.
 * @property memberModule Module for handling the specification and inspection of class members.
 */
class AnalysisConfiguration(
        val whitelist: Whitelist = Whitelist.MINIMAL,
        val pinnedClasses: Whitelist = Whitelist.PINNED_CLASSES,
        private val sandboxPrefix: String = "sandbox/",
        val classResolver: ClassResolver = ClassResolver(whitelist, pinnedClasses, sandboxPrefix),
        val minimumSeverityLevel: Severity = Severity.WARNING,
        val classPath: List<Path> = emptyList(),
        val analyzePinnedClasses: Boolean = false,
        val analyzeAnnotations: Boolean = false,
        val prefixFilters: List<String> = emptyList(),
        val classModule: ClassModule = ClassModule(),
        val memberModule: MemberModule = MemberModule()
)
