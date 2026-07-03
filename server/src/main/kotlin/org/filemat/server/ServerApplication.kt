package org.filemat.server

import org.filemat.server.common.State
import org.filemat.server.common.platform.Platform
import org.filemat.server.config.Props
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

@EnableScheduling
@EnableJdbcRepositories
@SpringBootApplication
class ServerApplication

fun main(args: Array<String>) {
	println("=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*=*")
	println("FILEMAT STARTING")
	Props; State

	val currentUser = System.getProperty("user.name")
	println("Current user: $currentUser")

	initializeFileStructure()
	if (System.getProperty("spring.datasource.url").isNullOrBlank() && System.getenv("SPRING_DATASOURCE_URL").isNullOrBlank()) {
		System.setProperty("spring.datasource.url", "jdbc:sqlite:${Props.databaseFilePath}")
	}
	runApplication<ServerApplication>(*args)
}


/**
 * Creates application folder structure
 */
fun initializeFileStructure() {
	println("Initializing data file structure.")

	val dbPath = Props.databaseFilePath
	val parentFolder = Props.dataFolderPath

	// Ensure parent directories exist
	if (!Files.exists(parentFolder)) {
		println("Creating parent directories for $dbPath")
		runCatching {
			Files.createDirectories(parentFolder)
		}.onFailure {
			println("\nFAILED TO CREATE FOLDERS:\n$dbPath")
			it.printStackTrace()
			exitProcess(1)
		}

		setPosixPermissionsIfSupported(parentFolder, "rwxr-x---", "application data folder")
	}

	// Ensure DB file exists
	if (!Files.exists(dbPath)) {
		runCatching {
			Files.createFile(dbPath)
		}.onFailure {
			println("Failed to create database file:")
			it.printStackTrace()
			exitProcess(1)
		}

		setPosixPermissionsIfSupported(dbPath, "rw-------", "database file")
	}
}

private fun setPosixPermissionsIfSupported(path: Path, permissions: String, description: String) {
	if (!Platform.isPosix) {
		println("Skipping POSIX permissions for $description on non-POSIX filesystem.")
		return
	}

	runCatching {
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
	}.onFailure {
		println("[!] Failed to set permissions for $description:")
		it.printStackTrace()
		exitProcess(1)
	}
}