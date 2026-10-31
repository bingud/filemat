package org.filemat.server

import org.filemat.server.config.Props
import org.filemat.server.config.SerialFilter
import org.filemat.server.config.platform.AclPolicy
import org.filemat.server.config.platform.ConsoleLogFile
import org.filemat.server.config.platform.DatabaseLocationPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.platform.WindowsNative
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

@EnableScheduling
@EnableJdbcRepositories
@SpringBootApplication
class ServerApplication

fun main(args: Array<String>) {
    println("=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*")
    println("FILEMAT STARTING")
    prepareStartup()
    initializeFileStructure()
    if (Platform.isWindows) {
        startLogFile()
        warnIfElevated()
    }

    runApplication<ServerApplication>(*args) {
        setDefaultProperties(mapOf("spring.datasource.url" to "jdbc:sqlite:${Props.databaseFile}"))
    }
}

/**
 * Startup steps before the first native call. Nothing here may load JNA: it unpacks its DLL on first use,
 * to `%TEMP%` when `jna.tmpdir` isn't set yet (test plan B2).
 */
internal fun prepareStartup() {
    SerialFilter.install()
    Props

    val currentUser = System.getProperty("user.name")
    println("Current user: $currentUser")

    if (Platform.isWindows) useNativeLibraryFolder()
}

/**
 * Creates application folder structure
 */
fun initializeFileStructure() {
    println("Initializing data file structure.")
    println("Data folder: ${Props.configuredDataFolderPath}")
    // Read before the location check: its first native call makes JNA create the native library folder inside
    val isNewDataFolder = !Props.configuredDataFolderPath.toFile().exists()
    val newNativeFolders = if (Platform.isWindows) nativeLibraryFolders().filterNot { Files.exists(it) } else emptyList()

    DatabaseLocationPolicy.check(Props.configuredDataFolderPath)?.let {
        println("\n[!] $it")
        exitProcess(1)
    }

    val dbPath = Props.databaseFile.toString()
    val dbFile = File(dbPath)
    val parentFolder = dbFile.parentFile

    // Ensure parent directories exist
    if (isNewDataFolder) {
        println("Creating parent directories for $dbPath")
        val result = parentFolder.mkdirs()
        if (!result && !parentFolder.exists()) {
            println("\nFAILED TO CREATE FOLDERS:\n$dbPath")
            exitProcess(1)
        }
    }

    if (Platform.isWindows) createNativeLibraryFolder(newNativeFolders)

    // Ensure DB file exists
    val isNewDatabase = !dbFile.exists()
    if (isNewDatabase) {
        runCatching {
            dbFile.createNewFile()
        }.onFailure {
            println("Failed to create database file:")
            it.printStackTrace()
            exitProcess(1)
        }
    }

    // Only a data folder Filemat creates is made private. On Windows that also rewrites the permissions of everything inside,
    // and FM_DATA_DIR can name a folder that holds other files, or a whole drive.
    if (isNewDataFolder) {
        restrictOrExit(parentFolder, "rwxr-x---", "application data folder", exitOnFailure = true)
    } else {
        warnIfNotPrivate(parentFolder)
    }
    restrictOrExit(dbFile, "rw-------", "database file", exitOnFailure = isNewDatabase)
}

/**
 * A data folder that existed before keeps its permissions, so Filemat only says when other accounts can access it
 */
private fun warnIfNotPrivate(folder: File) {
    val isPrivate = runCatching { AclPolicy.isRestricted(folder.toPath(), "rwxr-x---") }.getOrNull()
    if (isPrivate != false) return
    println("[!] Other accounts can access the data folder $folder. Filemat only makes a data folder private when it creates it.")
    println("[!] Restrict it to the account that runs Filemat, or set FM_DATA_DIR to a folder that doesn't exist yet.")
}

/**
 * Linux stops when it can't restrict a file it just created. Windows only warns (Q33), and so does Linux for files that
 * already existed, so a data folder that worked before still starts.
 */
private fun restrictOrExit(file: File, posixPermissions: String, name: String, exitOnFailure: Boolean) {
    runCatching {
        AclPolicy.restrictToOwner(file.toPath(), posixPermissions)
    }.onFailure {
        println("[!] Failed to set permissions for $name:")
        it.printStackTrace()
        if (exitOnFailure && !Platform.isWindows) exitProcess(1)
    }
}

/**
 * JNA and SQLite extract their DLLs to `<data>\native` instead of `%TEMP%`, where AppLocker or WDAC may block loading them.
 * Set before the first native call, the data folder check. Only sets the properties: the folder is created after that check.
 */
private fun useNativeLibraryFolder() {
    if (System.getProperty("jna.tmpdir") == null) System.setProperty("jna.tmpdir", Props.configuredDataFolderPath.resolve("native").toString())
    if (System.getProperty("org.sqlite.tmpdir") == null) System.setProperty("org.sqlite.tmpdir", System.getProperty("jna.tmpdir"))
}

private fun nativeLibraryFolders(): List<Path> {
    return listOfNotNull(System.getProperty("jna.tmpdir"), System.getProperty("org.sqlite.tmpdir")).distinct().map { Path.of(it) }
}

/**
 * SQLite doesn't create its folder, and JNA creates it only when it unpacks its DLL there (not when it finds one on the system).
 * The folders in [newFolders] didn't exist at start, so they are made private: other accounts must not replace the DLLs.
 */
private fun createNativeLibraryFolder(newFolders: List<Path>) {
    nativeLibraryFolders().forEach { folder ->
        runCatching {
            Files.createDirectories(folder)
        }.onFailure {
            println("\nFAILED TO CREATE THE NATIVE LIBRARY FOLDER:\n$folder")
            it.printStackTrace()
            exitProcess(1)
        }
        if (folder in newFolders) restrictOrExit(folder.toFile(), "rwx------", "native library folder", exitOnFailure = false)
    }
}

/**
 * The portable app has no service manager that keeps its output (Q53). Linux keeps logging to stdout only.
 */
private fun startLogFile() {
    val folder = Props.configuredDataFolderPath.resolve("logs")
    // Made private when Filemat creates it, like the data folder
    if (!Files.exists(folder)) {
        runCatching { Files.createDirectories(folder) }
            .onSuccess { restrictOrExit(folder.toFile(), "rwx------", "log folder", exitOnFailure = false) }
    }
    ConsoleLogFile.start(folder)
    println("Log folder: $folder")
}

/**
 * Filemat should run as a normal user. As an administrator it can read every user's files.
 */
private fun warnIfElevated() {
    when (WindowsNative.isElevatedOrSystem()) {
        false -> return
        null -> return println("\n[!] Filemat could not check whether it runs as an administrator. Run filemat.cmd from a normal, non-elevated window.\n")
        true -> Unit
    }
    println("""
        |
        |[!] ##########################################################
        |[!] Filemat is running as an administrator or as SYSTEM.
        |[!] It can read and change every user's files on this computer.
        |[!] Run filemat.cmd from a normal, non-elevated window instead.
        |[!] ##########################################################
        |""".trimMargin())
}
