package com.r3.corda.networkmanage.registration

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import joptsimple.OptionParser
import joptsimple.util.PathConverter
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.CertRole
import net.corda.node.utilities.registration.HTTPNetworkRegistrationService
import net.corda.node.utilities.registration.NetworkRegistrationHelper
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.nodeapi.internal.config.parseAs
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

fun main(args: Array<String>) {
    val optionParser = OptionParser()
    val configFileArg = optionParser
            .accepts("config-file", "The path to the registration config file")
            .withRequiredArg()
            .withValuesConvertedBy(PathConverter())

    val baseDirArg = optionParser
            .accepts("baseDir", "The registration tool's base directory, default to current directory.")
            .withRequiredArg()
            .withValuesConvertedBy(PathConverter())
            .defaultsTo(Paths.get("."))

    val optionSet = optionParser.parse(*args)
    val configFilePath = optionSet.valueOf(configFileArg)
    val baseDir = optionSet.valueOf(baseDirArg)

    val config = ConfigFactory.parseFile(configFilePath.toFile(), ConfigParseOptions.defaults().setAllowMissing(false))
            .resolve()
            .parseAs<RegistrationConfig>()

    val sslConfig = object : SSLConfiguration {
        override val keyStorePassword: String  by lazy { config.keyStorePassword ?: readPassword("Node Keystore password:") }
        override val trustStorePassword: String by lazy { config.trustStorePassword ?: readPassword("Node TrustStore password:") }
        override val certificatesDirectory: Path = baseDir
    }

    NetworkRegistrationHelper(sslConfig,
            config.legalName,
            config.email,
            HTTPNetworkRegistrationService(config.compatibilityZoneURL),
            config.networkRootTrustStorePath,
            config.networkRootTrustStorePassword ?: readPassword("Network trust root password:"), config.certRole).buildKeystore()
}

fun readPassword(fmt: String): String {
    return if (System.console() != null) {
        String(System.console().readPassword(fmt))
    } else {
        print(fmt)
        readLine() ?: ""
    }
}

data class RegistrationConfig(val legalName: CordaX500Name,
                              val email: String,
                              val compatibilityZoneURL: URL,
                              val networkRootTrustStorePath: Path,
                              val certRole: CertRole,
                              val keyStorePassword: String?,
                              val networkRootTrustStorePassword: String?,
                              val trustStorePassword: String?)
