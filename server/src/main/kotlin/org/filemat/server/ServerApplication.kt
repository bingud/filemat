package org.filemat.server

import org.filemat.server.common.State
import org.filemat.server.config.Props
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

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
	runApplication<ServerApplication>(*args)
}


/**
 * Creates application folder structure
 */
fun initializeFileStructure() {
	println("Initializing data file structure.")

	val dbPath = "/var/lib/filemat/filemat-server.db"
	val dbFile = File(dbPath)
	val parentFolder = dbFile.parentFile

	// Ensure parent directories exist
	if (!parentFolder.exists()) {
		println("Creating parent directories for $dbPath")
		val result = parentFolder.mkdirs()
		if (!result && !parentFolder.exists()) {
			println("\nFAILED TO CREATE FOLDERS:\n$dbPath")
			exitProcess(1)
		}

		try {
			Files.setPosixFilePermissions(parentFolder.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
		} catch (e: Exception) {
			println("[!] Failed to set permissions for application data folder:")
			e.printStackTrace()
			exitProcess(1)
		}
	}

	// Ensure DB file exists
	if (!dbFile.exists()) {
		runCatching {
			dbFile.createNewFile()
		}.onFailure {
			println("Failed to create database file:")
			it.printStackTrace()
			exitProcess(1)
		}

		runCatching {
			Files.setPosixFilePermissions(dbFile.toPath(), PosixFilePermissions.fromString("rw-------"))
		}.onFailure {
			println("Failed to set permissions for database file:")
			it.printStackTrace()
			exitProcess(1)
		}
	}
}