package org.filemat.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import java.io.File
import kotlin.system.exitProcess

@EnableJdbcRepositories
@SpringBootApplication
class ServerApplication

fun main(args: Array<String>) {
	val currentUser = System.getProperty("user.name")
	println("Current user: $currentUser")

	initializeFolderStructure()
	runApplication<ServerApplication>(*args)
}


fun initializeFolderStructure() {
	println("Initializing data folder structure.")

	val dbPath = "/var/lib/filemat/filemat-server.db"
	val dbFile = File(dbPath)

	// Ensure parent directories exist
	if (!dbFile.parentFile.exists()) {
		println("Creating parent directories for $dbPath")
		val result = dbFile.parentFile.mkdirs()
		if (!result || dbFile.exists()) {
			println("\nFAILED TO CREATE FOLDERS:\n$dbPath")
			exitProcess(1)
		}
	}
}