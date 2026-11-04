package com.thomaswcode.decrastination.sources.gmail

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Gmail's TLS (docs/spec/sources.md §Gmail) on a JVM, as the server's reads and writes have it, against a server of the
 * test's own: its certificate is made for `localhost` by the JDK's keytool, and trusted by the client alone.
 */
class GmailConnectionTest {
    private val folder: File = Files.createTempDirectory("gmail-tls").toFile()
    private val password = "made-up".toCharArray()
    private val keys: KeyStore = KeyStore.getInstance("PKCS12").apply {
        val file = File(folder, "keys.p12")
        keytool("-genkeypair", "-alias", "gmail", "-keyalg", "EC", "-keysize", "256", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", file.path, "-storepass", String(password), "-keypass", String(password))
        file.inputStream().use { load(it, password) }
    }
    private val server = SSLContext.getInstance("TLS").apply {
        init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers, null, null)
    }.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
    private val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
    private val trusting = SSLContext.getInstance("TLS").apply {
        val trusted = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("gmail", keys.getCertificate("gmail"))
        }
        init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }.trustManagers, null)
    }.socketFactory

    // One connection taken, its greeting sent once the handshake's through; a refused one's failure is the client's to see.
    private val serving = thread(isDaemon = true) {
        runCatching {
            server.accept().use { socket ->
                socket.outputStream.write("* OK Gmail ready\r\n".toByteArray())
                socket.outputStream.flush()
                socket.inputStream.read()
            }
        }
    }

    @AfterTest
    fun close() {
        server.close()
        serving.join(5_000)
        folder.deleteRecursively()
    }

    @Test
    fun `the right name gets through on a JVM, its certificate checked in the handshake`() {
        GmailConnection.open(address, "localhost", trusting).use { socket ->
            assertEquals("localhost", socket.session.peerHost)
            assertEquals("* OK Gmail ready", socket.inputStream.bufferedReader().readLine())
        }
    }

    @Test
    fun `a certificate made for another name is refused in the handshake, before anything's sent`() {
        assertFailsWith<SSLHandshakeException> { GmailConnection.open(address, "imap.example.test", trusting) }
    }

    private fun keytool(vararg args: String) {
        val bin = File(System.getProperty("java.home"), "bin")
        val tool = File(bin, "keytool.exe").takeIf { it.exists() } ?: File(bin, "keytool")
        val process = ProcessBuilder(tool.path, *args).redirectErrorStream(true).start()
        val said = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool: $said" }
    }
}
