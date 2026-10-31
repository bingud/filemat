import com.github.gradle.node.npm.task.NpmTask
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import org.apache.tools.ant.taskdefs.condition.Os
import java.io.OutputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	kotlin("plugin.serialization") version "2.1.10"
	id("org.springframework.boot") version "3.4.2"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.github.node-gradle.node") version "7.1.0"
}

group = "org.filemat"
version = "app"


repositories {
	mavenCentral()
}

dependencies {
	implementation("com.atlassian:onetime:2.2.0")
	implementation("org.apache.tika:tika-core:3.2.3")
	implementation("me.desair.tus:tus-java-server:1.0.0-3.0")
	implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")

	implementation("jakarta.mail:jakarta.mail-api:2.1.3")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.komamitsu:spring-data-sqlite:1.4.0")
	implementation("org.flywaydb:flyway-core:11.15.0")
	implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.0")
	implementation("com.github.f4b6a3:ulid-creator:5.2.3")
	implementation("org.springframework.security:spring-security-crypto:6.5.7")
	implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	// Windows API calls (token elevation, drive type, account names)
	implementation("net.java.dev.jna:jna-platform:5.14.0")

	testImplementation("org.springframework.boot:spring-boot-starter-test") {
		exclude(module = "junit")
		exclude(module = "mockito-core")
	}
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("io.mockk:mockk-jvm:1.14.6")

	implementation("org.bytedeco:javacv:1.5.11")
	runtimeOnly("org.bytedeco:ffmpeg:7.1-1.5.11:linux-x86_64")
	runtimeOnly("org.bytedeco:ffmpeg:7.1-1.5.11:linux-arm64")
	implementation("com.drewnoakes:metadata-extractor:2.18.0")

	implementation("net.coobird:thumbnailator:0.4.21")
	val twelvemonkeysVersion = "3.12.0"
	// Core TwelveMonkeys
	implementation("com.twelvemonkeys.imageio:imageio-core:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-metadata:$twelvemonkeysVersion")

	// Common Format Plugins
	implementation("com.twelvemonkeys.imageio:imageio-jpeg:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-tiff:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-bmp:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-webp:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-psd:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-icns:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-pnm:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-pcx:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-tga:$twelvemonkeysVersion")
	implementation("com.twelvemonkeys.imageio:imageio-hdr:$twelvemonkeysVersion")
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Starting the app writes a setup code to the data folder, so tests get their own instead of /var/lib/filemat or %LOCALAPPDATA%\Filemat
	val testDataFolder = layout.buildDirectory.dir("test-data-folder").get().asFile
	environment("FM_DATA_DIR", testDataFolder.absolutePath)
	doFirst { testDataFolder.mkdirs() }
	// Make the link and Windows feature tests fail instead of skip (testutil), for example gradlew test -Dfilemat.requireWindowsFeatures=true
	listOf("filemat.requireSymlinks", "filemat.requireWindowsFeatures").forEach { name ->
		System.getProperty(name)?.let { systemProperty(name, it) }
	}
}


// Web app (Q10). Uses the installed node and npm.

node {
	download = false
	nodeProjectDir = file("../web")
	npmInstallCommand = "ci"
}

val webBuild = tasks.register<NpmTask>("webBuild") {
	description = "Builds the web app in ../web."
	dependsOn(tasks.named("npmInstall"))
	npmCommand = listOf("run", "build")
	inputs.dir("../web/src")
	inputs.dir("../web/static")
	inputs.files("../web/package.json", "../web/package-lock.json", "../web/svelte.config.js", "../web/vite.config.ts", "../web/tsconfig.json")
	outputs.dir("../web/build")
}

tasks.bootJar {
	// Served from classpath:/static. -PskipWeb builds the server alone.
	if (!project.hasProperty("skipWeb")) {
		from(webBuild) { into("BOOT-INF/classes/static") }
	}
}

// A leftover src/main/resources/static would put every file in the JAR twice, and with duplicatesStrategy EXCLUDE
// the old file would win, because resources come before the web build. The web build is the only source of static files.
tasks.processResources {
	if (!project.hasProperty("skipWeb")) exclude("static/**")
}


// Portable Windows zips (§8.1): gradlew.bat windowsDist -PreleaseVersion=1.2.3 builds x64 and arm64

val releaseVersion = providers.gradleProperty("releaseVersion").getOrElse("dev")

// BtbN GPL builds, bumped each release (Q46). 7.1 matches the javacv FFmpeg; BtbN keeps month-end builds for 2 years.
val ffmpegRelease = "autobuild-2026-07-31-14-10"

// Temurin (GPLv2 with Classpath Exception) may be redistributed. Pinned, because the x64 JDK's jlink builds both runtimes
// and only links jmods of its own exact version. Bump with https://api.adoptium.net/v3/assets/latest/21/hotspot?os=windows
val temurinRelease = "jdk-21.0.12.1+1"

data class WindowsTarget(
	val arch: String,
	val ffmpegBuild: String,
	val ffmpegSha256: String,
	val jdkZip: String,
	val jdkSha256: String,
) {
	val taskSuffix get() = arch.replaceFirstChar { it.uppercase() }
}

val windowsTargets = listOf(
	WindowsTarget(
		arch = "x64",
		ffmpegBuild = "ffmpeg-n7.1.5-12-g1fdbca85aa-win64-gpl-7.1",
		ffmpegSha256 = "c067a1ca58f4fc4449f4bab0890fbcd65cbb3e5f46e066cf9c768e06c0c1d4d9",
		jdkZip = "OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip",
		jdkSha256 = "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e",
	),
	WindowsTarget(
		arch = "arm64",
		ffmpegBuild = "ffmpeg-n7.1.5-12-g1fdbca85aa-winarm64-gpl-7.1",
		ffmpegSha256 = "94e2b89465ac6a653246cb8314fae33734e1c3c2b42306e274d7d3087b8b26dd",
		jdkZip = "OpenJDK21U-jdk_aarch64_windows_hotspot_21.0.12.1_1.zip",
		jdkSha256 = "ccf2e51f527d542a70ba5794a600d3aac04b4e967950e227834c7566cb1bec7b",
	),
)

// From jdeps on the server JAR, plus modules only used at runtime (TLS, charsets, zip file systems, OperatingSystemMXBean)
val jreModules = listOf(
	"java.base", "java.compiler", "java.desktop", "java.instrument", "java.logging", "java.management", "java.naming",
	"java.net.http", "java.prefs", "java.rmi", "java.scripting", "java.security.jgss", "java.sql", "java.sql.rowset",
	"java.transaction.xa", "java.xml", "jdk.charsets", "jdk.crypto.ec", "jdk.jfr", "jdk.management", "jdk.net",
	"jdk.unsupported", "jdk.zipfs",
)

fun sha256(file: File): String {
	val digest = MessageDigest.getInstance("SHA-256")
	DigestInputStream(file.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
	return digest.digest().joinToString("") { "%02x".format(it) }
}

val windowsWorkDir = layout.buildDirectory.dir("windows")

/** Downloads [url] to build/windows/downloads unless a file with [sha256] is already there. */
fun registerDownload(taskName: String, url: String, sha256: String): Pair<TaskProvider<Task>, Provider<RegularFile>> {
	val target = windowsWorkDir.map { it.file("downloads/${url.substringAfterLast('/')}") }
	val task = tasks.register(taskName) {
		description = "Downloads ${url.substringAfterLast('/')} and checks its SHA-256."
		inputs.property("sha256", sha256)
		outputs.file(target)
		doLast {
			val file = target.get().asFile
			if (!file.isFile || sha256(file) != sha256) {
				file.parentFile.mkdirs()
				URI(url).toURL().openStream().use { input -> file.outputStream().use { input.transferTo(it) } }
			}
			val actual = sha256(file)
			if (actual != sha256) {
				file.delete()
				throw GradleException("${file.name} has SHA-256 $actual, expected $sha256.")
			}
		}
	}
	return task to target
}

// The x64 JDK is extracted whole, since its jlink runs the build. The others only give their jmods.
val extractedJdks = windowsTargets.associate { target ->
	val (download, zip) = registerDownload(
		"downloadJdk${target.taskSuffix}",
		"https://github.com/adoptium/temurin21-binaries/releases/download/${temurinRelease.replace("+", "%2B")}/${target.jdkZip}",
		target.jdkSha256,
	)
	target.arch to tasks.register<Sync>("extractJdk${target.taskSuffix}") {
		dependsOn(download)
		from(zipTree(zip)) {
			if (target.arch != "x64") include("*/jmods/**")
			// drop the jdk-21.0.12.1+1 top folder
			eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
		}
		includeEmptyDirs = false
		into(windowsWorkDir.map { it.dir("${target.arch}/jdk") })
	}
}
val hostJdk = extractedJdks.getValue("x64")

val windowsDists = windowsTargets.map { target ->
	val workDir = windowsWorkDir.map { it.dir(target.arch) }

	val (downloadFfmpeg, ffmpegZip) = registerDownload(
		"downloadFfmpeg${target.taskSuffix}",
		"https://github.com/BtbN/FFmpeg-Builds/releases/download/$ffmpegRelease/${target.ffmpegBuild}.zip",
		target.ffmpegSha256,
	)

	val extractFfmpeg = tasks.register<Sync>("extractFfmpeg${target.taskSuffix}") {
		dependsOn(downloadFfmpeg)
		from(zipTree(ffmpegZip)) {
			include("*/bin/ffmpeg.exe", "*/LICENSE.txt")
			eachFile { relativePath = RelativePath(true, name) }
		}
		includeEmptyDirs = false
		into(workDir.map { it.dir("ffmpeg") })
	}

	val targetJdk = extractedJdks.getValue(target.arch)
	val jre = tasks.register<Exec>("windowsJre${target.taskSuffix}") {
		description = "Builds a trimmed ${target.arch} Java runtime with jlink."
		dependsOn(hostJdk, targetJdk)
		val output = workDir.map { it.dir("jre") }
		inputs.property("modules", jreModules)
		inputs.property("jdk", target.jdkSha256)
		outputs.dir(output)
		doFirst {
			if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
				throw GradleException("windowsDist runs on Windows only: it runs jlink.exe from the Windows JDK.")
			}
			// jlink refuses an existing output folder
			delete(output)
			executable = hostJdk.get().destinationDir.resolve("bin/jlink.exe").path
			args(
				"--module-path", targetJdk.get().destinationDir.resolve("jmods").path,
				"--add-modules", jreModules.joinToString(","),
				"--strip-debug", "--no-man-pages", "--no-header-files", "--compress=zip-6",
				"--output", output.get().asFile.path,
			)
		}
	}

	tasks.register<Zip>("windowsDist${target.taskSuffix}") {
		group = "distribution"
		description = "Builds the portable Windows ${target.arch} zip (JAR, Java runtime, FFmpeg, filemat.cmd)."
		archiveFileName = "filemat-$releaseVersion-windows-${target.arch}.zip"
		destinationDirectory = layout.buildDirectory.dir("dist")
		into("filemat") {
			from(tasks.bootJar) { rename { "filemat.jar" } }
			// cmd.exe misreads labels and goto in LF batch files (§8.4)
			from("src/packaging/windows/filemat.cmd") {
				filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("crlf"))
			}
			into("jre") { from(jre) }
			into("ffmpeg") { from(extractFfmpeg) { include("ffmpeg.exe") } }
			into("LICENSES") {
				from("../LICENSE") { rename { "Filemat-GPL-3.0.txt" } }
				from(extractFfmpeg) {
					include("LICENSE.txt")
					rename { "FFmpeg-GPL-3.0.txt" }
				}
				from("src/packaging/windows/THIRD-PARTY.txt") {
					filter<ReplaceTokens>("tokens" to mapOf(
						"FFMPEG_BUILD" to target.ffmpegBuild,
						"FFMPEG_RELEASE" to ffmpegRelease,
						"TEMURIN_RELEASE" to temurinRelease,
					))
					filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("crlf"))
				}
			}
		}
	}
}

tasks.register("windowsDist") {
	group = "distribution"
	description = "Builds the portable Windows zips for x64 and arm64."
	dependsOn(windowsDists)
}
