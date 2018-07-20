package net.corda.testing.node.internal

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import net.corda.core.internal.createDirectories
import net.corda.core.internal.deleteIfExists
import net.corda.core.internal.outputStream
import net.corda.node.internal.cordapp.createTestManifest
import net.corda.testing.driver.TestCorDapp
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.*
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.reflect.KClass

internal fun Iterable<JarEntryInfo>.packageToCorDapp(path: Path, name: String, version: String, vendor: String, title: String = name, willResourceBeAddedBeToCorDapp: (String, URL) -> Boolean = { _, _ -> true }) {

    var hasContent = false
    try {
        hasContent = packageToCorDapp(path.outputStream(), name, version, vendor, title, willResourceBeAddedBeToCorDapp)
    } finally {
        if (!hasContent) {
            path.deleteIfExists()
        }
    }
}

internal fun Iterable<JarEntryInfo>.packageToCorDapp(outputStream: OutputStream, name: String, version: String, vendor: String, title: String = name, willResourceBeAddedBeToCorDapp: (String, URL) -> Boolean = { _, _ -> true }): Boolean {

    val manifest = createTestManifest(name, title, version, vendor)
    return JarOutputStream(outputStream, manifest).use { jos -> zip(jos, willResourceBeAddedBeToCorDapp) }
}

internal fun Class<*>.jarEntryInfo(): JarEntryInfo {

    return JarEntryInfo.ClassJarEntryInfo(this)
}

fun Iterable<TestCorDapp>.packageInDirectory(directory: Path) {

    directory.createDirectories()
    forEach { cordapp -> cordapp.packageAsJarInDirectory(directory) }
}

fun allClassesForPackage(targetPackage: String): Set<Class<*>> {

    val scanResult = FastClasspathScanner(targetPackage).strictWhitelist().scan()
    // TODO sollecitom fix this (the resources are not found)
    return scanResult.namesOfAllClasses.filter { className -> className.startsWith(targetPackage) }.map(scanResult::classNameToClassRef).toSet()
}

fun cordappsForPackages(packages: Iterable<String>): Set<TestCorDapp> {

    return simplifyScanPackages(packages).toSet().fold(emptySet()) { all, packageName -> all + testCorDapp(packageName) }
}

fun cordappsForPackages(firstPackage: String, vararg otherPackages: String): Set<TestCorDapp> {

    return cordappsForPackages(setOf(*otherPackages) + firstPackage)
}

fun getCallerClass(directCallerClass: KClass<*>): Class<*>? {

    val stackTrace = Throwable().stackTrace
    val index = stackTrace.indexOfLast { it.className == directCallerClass.java.name }
    if (index == -1) return null
    return try {
        Class.forName(stackTrace[index + 1].className)
    } catch (e: ClassNotFoundException) {
        null
    }
}

fun getCallerPackage(directCallerClass: KClass<*>): String? {

    return getCallerClass(directCallerClass)?.`package`?.name
}

private fun testCorDapp(packageName: String): TestCorDapp {

    val uuid = UUID.randomUUID()
    val name = "$packageName-$uuid"
    val version = "$uuid"
    return TestCorDapp.Factory.create(name, version).plusPackage(packageName)
}

private fun simplifyScanPackages(scanPackages: Iterable<String>): List<String> {

    return scanPackages.sorted().fold(emptyList()) { listSoFar, packageName ->
        when {
            listSoFar.isEmpty() -> listOf(packageName)
            packageName.startsWith(listSoFar.last()) -> listSoFar  // Squash ["com.foo", "com.foo.bar"] into just ["com.foo"]
            else -> listSoFar + packageName
        }
    }
}

internal fun String.packageToPath() = replace(".", File.separator)

private fun Iterable<JarEntryInfo>.zip(outputStream: ZipOutputStream, willResourceBeAddedBeToCorDapp: (String, URL) -> Boolean): Boolean {

    val entries = filter { (fullyQualifiedName, url) -> willResourceBeAddedBeToCorDapp(fullyQualifiedName, url) }
    if (entries.isNotEmpty()) {
        zip(outputStream, entries)
    }
    return entries.isNotEmpty()
}

private fun zip(outputStream: ZipOutputStream, allInfo: Iterable<JarEntryInfo>) {

    val time = FileTime.from(Instant.now())
    val classLoader = Thread.currentThread().contextClassLoader
    allInfo.distinctBy { it.url }.forEach { info ->

        try {
            val entry = ZipEntry(info.entryName).setCreationTime(time).setLastAccessTime(time).setLastModifiedTime(time)
            outputStream.putNextEntry(entry)
            classLoader.getResourceAsStream(info.entryName).use {
                IOUtils.copy(it, outputStream)
            }
        } finally {
            outputStream.closeEntry()
        }
    }
}

internal sealed class JarEntryInfo(val fullyQualifiedName: String, val url: URL) {

    abstract val entryName: String

    class ClassJarEntryInfo(val clazz: Class<*>) : JarEntryInfo(clazz.name, clazz.classFileURL()) {

        override val entryName = "${fullyQualifiedName.packageToPath()}$fileExtensionSeparator$classFileExtension"
    }

    class ResourceJarEntryInfo(fullyQualifiedName: String, url: URL) : JarEntryInfo(fullyQualifiedName, url) {

        override val entryName: String
            get() {
                val extensionIndex = fullyQualifiedName.lastIndexOf(fileExtensionSeparator)
                return "${fullyQualifiedName.substring(0 until extensionIndex).packageToPath()}${fullyQualifiedName.substring(extensionIndex)}"
            }
    }

    operator fun component1(): String = fullyQualifiedName

    operator fun component2(): URL = url

    private companion object {

        private const val classFileExtension = "class"
        private const val fileExtensionSeparator = "."
        private const val whitespace = " "
        private const val whitespaceReplacement = "%20"

        private fun Class<*>.classFileURL(): URL {

            require(protectionDomain?.codeSource?.location != null) { "Invalid class $name for test CorDapp. Classes without protection domain cannot be referenced. This typically happens for Java / Kotlin types." }
            return URI.create("${protectionDomain.codeSource.location}/${name.packageToPath()}$fileExtensionSeparator$classFileExtension".escaped()).toURL()
        }

        private fun String.escaped(): String = this.replace(whitespace, whitespaceReplacement)
    }
}