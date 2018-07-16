package net.corda.testing.node.internal

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import net.corda.core.internal.*
import net.corda.testing.driver.TestCorDapp
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// TODO sollecitom, perhaps create a TestCorDappPackager class, rather than extension functions

// TODO sollecitom
fun Iterable<Class<*>>.packageToCorDapp(path: Path, name: String, version: String, vendor: String, title: String = name, willClassBeAddedBeToCorDapp: (TestCorDapp.JarEntryInfo) -> Boolean = { true }) {

    var hasContent = false
    try {
        hasContent = packageToCorDapp(path.outputStream(), name, version, vendor, title, willClassBeAddedBeToCorDapp)
    } finally {
        if (!hasContent) {
            path.deleteIfExists()
        }
    }
}

// TODO sollecitom - try and remove this ClassLoader argument (it's only used to figure out the out folder)
fun Iterable<Class<*>>.packageToCorDapp(outputStream: OutputStream, name: String, version: String, vendor: String, title: String = name, willClassBeAddedBeToCorDapp: (TestCorDapp.JarEntryInfo) -> Boolean = { true }): Boolean {

    val manifest = createTestManifest(name, title, version, vendor)
    return JarOutputStream(outputStream, manifest).use { jos -> zip(jos, willClassBeAddedBeToCorDapp) }
}

// TODO sollecitom
fun Package.allClasses(): Set<Class<*>> {

    return allClassesForPackage(name)
}

// TODO sollecitom
fun allClassesForPackage(targetPackage: String): Set<Class<*>> {

    val scanResult = FastClasspathScanner(targetPackage).scan()
    return scanResult.namesOfAllClasses.filter { it.startsWith(targetPackage) }.map(scanResult::classNameToClassRef).toSet()
}

// TODO sollecitom
fun String.packageToPath() = replace(".", File.separator)

// TODO sollecitom
private fun Iterable<Class<*>>.zip(outputStream: ZipOutputStream, willClassBeAddedBeToCorDapp: (TestCorDapp.JarEntryInfo) -> Boolean): Boolean {

    val illegal = filter { it.protectionDomain?.codeSource?.location == null }
    if (illegal.isNotEmpty()) {
        throw IllegalArgumentException("Some classes do not have a location, typically because they are part of Java or Kotlin. Offending types were: ${illegal.joinToString(", ", "[", "]") { it.simpleName }}")
    }
    val entries = map(Class<*>::jarInfo).filter(willClassBeAddedBeToCorDapp)
    if (entries.isNotEmpty()) {
        zip(outputStream, entries)
    }
    return entries.isNotEmpty()
}

// TODO sollecitom
private fun zip(outputStream: ZipOutputStream, allInfo: Iterable<TestCorDapp.JarEntryInfo>) {

    val time = FileTime.from(Instant.now())
    allInfo.distinctBy { it.url }.forEach { info ->

        val path = info.url.toPath()
        val entryPath = "${info.fullyQualifiedName.packageToPath()}.class"
        val entry = ZipEntry(entryPath).setCreationTime(time).setLastAccessTime(time).setLastModifiedTime(time)
        outputStream.putNextEntry(entry)
        if (path.isRegularFile()) {
            path.copyTo(outputStream)
        }
        outputStream.closeEntry()
    }
}

// TODO sollecitom
private fun Class<*>.jarInfo(): TestCorDapp.JarEntryInfo {

    return TestCorDapp.JarEntryInfo(name, classFileURL())
}

// TODO sollecitom
private fun Class<*>.classFileURL(): URL {

    // TODO sollecitom refactor the whitespace fix not to hardcode strings
    return URI.create("${protectionDomain.codeSource.location}/${name.packageToPath()}.class".replace(" ", "%20")).toURL()
}

// TODO sollecitom move to utils
private fun createTestManifest(name: String, title: String, version: String, vendor: String): Manifest {

    val manifest = Manifest()

    // Mandatory manifest attribute. If not present, all other entries are silently skipped.
    manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"

    manifest["Name"] = name

    manifest["Specification-Title"] = title
    manifest["Specification-Version"] = version
    manifest["Specification-Vendor"] = vendor

    manifest["Implementation-Title"] = title
    manifest["Implementation-Version"] = version
    manifest["Implementation-Vendor"] = vendor

    return manifest
}

// TODO sollecitom move to utils
private operator fun Manifest.set(key: String, value: String) {

    mainAttributes.putValue(key, value)
}