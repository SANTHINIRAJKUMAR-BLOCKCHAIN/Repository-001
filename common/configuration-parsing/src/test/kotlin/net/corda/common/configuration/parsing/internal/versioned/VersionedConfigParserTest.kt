package net.corda.common.configuration.parsing.internal.versioned

import net.corda.common.configuration.parsing.internal.configObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class VersionedConfigParserTest {

    private val extractVersion: ConfigVersionExtractor = KeyedConfigVersionExtractor("configuration.metadata.version")

    @Test
    fun configuration_with_expected_version_gets_parsed_with_mapping() {

        val version = 2
        val expectedParsedConfiguration = "Success"
        val rawConfiguration = configObject("configuration" to configObject("metadata" to configObject("version" to version), "node" to configObject("p2pAddress" to "localhost:8080"))).toConfig()

        val parseConfig: ParseConfig<String> = VersionedConfigParser.mapping(extractVersion, 1 to { _ -> "Failure" }, version to { _ -> expectedParsedConfiguration }, 3 to { _ -> "Failure" })

        val parsedConfiguration = parseConfig.invoke(rawConfiguration)

        assertThat(parsedConfiguration).isEqualTo(expectedParsedConfiguration)
    }

    @Test
    fun missing_version_results_in_mapping_for_zero_by_default() {

        val rawConfiguration = configObject().toConfig()
        val expectedParsedConfiguration = "Success"

        val parseConfig: ParseConfig<String> = VersionedConfigParser.mapping({ _ -> null }, 0 to { _ -> expectedParsedConfiguration })

        val parsedConfiguration = parseConfig.invoke(rawConfiguration)

        assertThat(parsedConfiguration).isEqualTo(expectedParsedConfiguration)
    }

    @Test
    fun missing_version_results_in_exception_if_default_version_is_set_to_null() {

        val rawConfiguration = configObject().toConfig()
        val expectedParsedConfiguration = "Success"

        val parseConfig: ParseConfig<String> = VersionedConfigParser.mapping({ _ -> null }, 0 to { _ -> expectedParsedConfiguration }, defaultVersion = null)

        assertThatThrownBy { parseConfig.invoke(rawConfiguration) }.isInstanceOf(VersionedConfigParser.Exception.MissingVersionHeader::class.java)
    }

    @Test
    fun missing_version_results_in_mapping_for_default_version() {

        val rawConfiguration = configObject().toConfig()
        val defaultVersion = 42
        val expectedParsedConfiguration = "Success"

        val parseConfig: ParseConfig<String> = VersionedConfigParser.mapping({ _ -> null }, defaultVersion to { _ -> expectedParsedConfiguration }, defaultVersion = defaultVersion)

        val parsedConfiguration = parseConfig.invoke(rawConfiguration)

        assertThat(parsedConfiguration).isEqualTo(expectedParsedConfiguration)
    }

    @Test
    fun unmapped_version_results_in_exception() {

        val rawConfiguration = configObject().toConfig()
        val expectedParsedConfiguration = "Success"

        val parseConfig: ParseConfig<String> = VersionedConfigParser.mapping({ _ -> 2 }, 1 to { _ -> expectedParsedConfiguration }, 3 to { _ -> expectedParsedConfiguration })

        assertThatThrownBy { parseConfig.invoke(rawConfiguration) }.isInstanceOf(VersionedConfigParser.Exception.UnsupportedVersion::class.java)
    }
}