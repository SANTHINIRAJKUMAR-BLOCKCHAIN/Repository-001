package net.corda.nodeapi.internal

import net.corda.core.messaging.ClientRpcSslOptions
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.nodeapi.BrokerRpcSslOptions
import net.corda.nodeapi.internal.config.CertificateStore
import net.corda.nodeapi.internal.config.FileBasedCertificateStoreSupplier
import net.corda.nodeapi.internal.config.SslConfiguration
import net.corda.nodeapi.internal.config.TwoWaySslConfiguration
import org.apache.activemq.artemis.api.core.TransportConfiguration
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants
import java.nio.file.Path

/** Class to set Artemis TCP configuration options. */
class InternalArtemisTcpTransport {
    companion object {
        /**
         * Corda supported TLS schemes.
         * <p><ul>
         * <li>TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
         * <li>TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
         * <li>TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
         * </ul></p>
         * As shown above, current version restricts enabled TLS cipher suites to:
         * AES128 using Galois/Counter Mode (GCM) for the block cipher being used to encrypt the message stream.
         * SHA256 as message authentication algorithm.
         * Ephemeral Diffie Hellman key exchange for advanced forward secrecy. ECDHE is preferred, but DHE is also
         * supported in case one wants to completely avoid the use of ECC for TLS.
         * ECDSA and RSA for digital signatures. Our self-generated certificates all use ECDSA for handshakes,
         * but we allow classical RSA certificates to work in case one uses external tools or cloud providers or HSMs
         * that do not support ECC certificates.
         */
        val CIPHER_SUITES = listOf(
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
        )

        /** Supported TLS versions, currently TLSv1.2 only. */
        val TLS_VERSIONS = listOf("TLSv1.2")

        internal fun defaultArtemisOptions(hostAndPort: NetworkHostAndPort) = mapOf(
                // Basic TCP target details.
                TransportConstants.HOST_PROP_NAME to hostAndPort.host,
                TransportConstants.PORT_PROP_NAME to hostAndPort.port,

                // Turn on AMQP support, which needs the protocol jar on the classpath.
                // Unfortunately we cannot disable core protocol as artemis only uses AMQP for interop.
                // It does not use AMQP messages for its own messages e.g. topology and heartbeats.
                // TODO further investigate how to ensure we use a well defined wire level protocol for Node to Node communications.
                TransportConstants.PROTOCOLS_PROP_NAME to "CORE,AMQP",
                TransportConstants.USE_GLOBAL_WORKER_POOL_PROP_NAME to (nodeSerializationEnv != null),
                TransportConstants.REMOTING_THREADS_PROPNAME to (if (nodeSerializationEnv != null) -1 else 1),
                // turn off direct delivery in Artemis - this is latency optimisation that can lead to
                //hick-ups under high load (CORDA-1336)
                TransportConstants.DIRECT_DELIVER to false)

        internal val defaultSSLOptions = mapOf(
                TransportConstants.ENABLED_CIPHER_SUITES_PROP_NAME to CIPHER_SUITES.joinToString(","),
                TransportConstants.ENABLED_PROTOCOLS_PROP_NAME to TLS_VERSIONS.joinToString(","))

        private fun SslConfiguration.toTransportOptions(): Map<String, Any> {

            val options = mutableMapOf<String, Any>()
            (keyStore to trustStore).addToTransportOptions(options)
            return options
        }

        private fun Pair<FileBasedCertificateStoreSupplier?, FileBasedCertificateStoreSupplier?>.addToTransportOptions(options: MutableMap<String, Any>) {

            val keyStore = first
            val trustStore = second
            keyStore?.let {
                with (it) {
                    path.requireOnDefaultFileSystem()
                    options.putAll(get().toKeyStoreTransportOptions(path))
                }
            }
            trustStore?.let {
                with (it) {
                    path.requireOnDefaultFileSystem()
                    options.putAll(get().toTrustStoreTransportOptions(path))
                }
            }
        }

        private fun CertificateStore.toKeyStoreTransportOptions(path: Path) = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.KEYSTORE_PROVIDER_PROP_NAME to "JKS",
                TransportConstants.KEYSTORE_PATH_PROP_NAME to path,
                TransportConstants.KEYSTORE_PASSWORD_PROP_NAME to password,
                TransportConstants.NEED_CLIENT_AUTH_PROP_NAME to true)

        private fun CertificateStore.toTrustStoreTransportOptions(path: Path) = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.TRUSTSTORE_PROVIDER_PROP_NAME to "JKS",
                TransportConstants.TRUSTSTORE_PATH_PROP_NAME to path,
                TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME to password,
                TransportConstants.NEED_CLIENT_AUTH_PROP_NAME to true)

        private fun ClientRpcSslOptions.toTransportOptions() = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.TRUSTSTORE_PROVIDER_PROP_NAME to trustStoreProvider,
                TransportConstants.TRUSTSTORE_PATH_PROP_NAME to trustStorePath,
                TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME to trustStorePassword)

        private fun BrokerRpcSslOptions.toTransportOptions() = mapOf(
                TransportConstants.SSL_ENABLED_PROP_NAME to true,
                TransportConstants.KEYSTORE_PROVIDER_PROP_NAME to "JKS",
                TransportConstants.KEYSTORE_PATH_PROP_NAME to keyStorePath,
                TransportConstants.KEYSTORE_PASSWORD_PROP_NAME to keyStorePassword,
                TransportConstants.NEED_CLIENT_AUTH_PROP_NAME to false)

        internal val acceptorFactoryClassName = "org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptorFactory"
        internal val connectorFactoryClassName = NettyConnectorFactory::class.java.name

        fun p2pAcceptorTcpTransport(hostAndPort: NetworkHostAndPort, config: TwoWaySslConfiguration?, enableSSL: Boolean = true): TransportConfiguration {

            return p2pAcceptorTcpTransport(hostAndPort, config?.keyStore, config?.trustStore, enableSSL = enableSSL)
        }

        fun p2pConnectorTcpTransport(hostAndPort: NetworkHostAndPort, config: TwoWaySslConfiguration?, enableSSL: Boolean = true): TransportConfiguration {

            return p2pConnectorTcpTransport(hostAndPort, config?.keyStore, config?.trustStore, enableSSL = enableSSL)
        }

        fun p2pAcceptorTcpTransport(hostAndPort: NetworkHostAndPort, keyStore: FileBasedCertificateStoreSupplier?, trustStore: FileBasedCertificateStoreSupplier?, enableSSL: Boolean = true): TransportConfiguration {

            val options = defaultArtemisOptions(hostAndPort).toMutableMap()
            if (enableSSL) {
                options.putAll(defaultSSLOptions)
                (keyStore to trustStore).addToTransportOptions(options)
            }
            return TransportConfiguration(acceptorFactoryClassName, options)
        }

        fun p2pConnectorTcpTransport(hostAndPort: NetworkHostAndPort, keyStore: FileBasedCertificateStoreSupplier?, trustStore: FileBasedCertificateStoreSupplier?, enableSSL: Boolean = true): TransportConfiguration {

            val options = defaultArtemisOptions(hostAndPort).toMutableMap()
            if (enableSSL) {
                options.putAll(defaultSSLOptions)
                (keyStore to trustStore).addToTransportOptions(options)
            }
            return TransportConfiguration(connectorFactoryClassName, options)
        }

        /** [TransportConfiguration] for RPC TCP communication - server side. */
        fun rpcAcceptorTcpTransport(hostAndPort: NetworkHostAndPort, config: BrokerRpcSslOptions?, enableSSL: Boolean = true): TransportConfiguration {
            val options = defaultArtemisOptions(hostAndPort).toMutableMap()

            if (config != null && enableSSL) {
                config.keyStorePath.requireOnDefaultFileSystem()
                options.putAll(config.toTransportOptions())
                options.putAll(defaultSSLOptions)
            }
            return TransportConfiguration(acceptorFactoryClassName, options)
        }

        /** [TransportConfiguration] for RPC TCP communication
         * This is the Transport that connects the client JVM to the broker. */
        fun rpcConnectorTcpTransport(hostAndPort: NetworkHostAndPort, config: ClientRpcSslOptions?, enableSSL: Boolean = true): TransportConfiguration {
            val options = defaultArtemisOptions(hostAndPort).toMutableMap()

            if (config != null && enableSSL) {
                config.trustStorePath.requireOnDefaultFileSystem()
                options.putAll(config.toTransportOptions())
                options.putAll(defaultSSLOptions)
            }
            return TransportConfiguration(connectorFactoryClassName, options)
        }

        /** Create as list of [TransportConfiguration]. **/
        fun rpcConnectorTcpTransportsFromList(hostAndPortList: List<NetworkHostAndPort>, config: ClientRpcSslOptions?, enableSSL: Boolean = true): List<TransportConfiguration> = hostAndPortList.map {
            rpcConnectorTcpTransport(it, config, enableSSL)
        }

        fun rpcInternalClientTcpTransport(hostAndPort: NetworkHostAndPort, config: SslConfiguration): TransportConfiguration {
            return TransportConfiguration(connectorFactoryClassName, defaultArtemisOptions(hostAndPort) + defaultSSLOptions + config.toTransportOptions())
        }

        fun rpcInternalAcceptorTcpTransport(hostAndPort: NetworkHostAndPort, config: SslConfiguration): TransportConfiguration {
            return TransportConfiguration(acceptorFactoryClassName, defaultArtemisOptions(hostAndPort) + defaultSSLOptions + config.toTransportOptions())
        }
    }
}