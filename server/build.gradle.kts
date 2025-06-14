plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	kotlin("plugin.serialization") version "2.1.10"
	id("org.springframework.boot") version "3.4.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "org.filemat"
version = "app"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	val jakartaMail = "2.1.3"
	val springCrypto = "6.4.4"
	val coroutinesCore = "1.10.1"
	///

	implementation("org.apache.tika:tika-core:3.1.0")
	implementation("me.desair.tus:tus-java-server:1.0.0-3.0")

	implementation("jakarta.mail:jakarta.mail-api:$jakartaMail")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.komamitsu:spring-data-sqlite:1.0.0")
	implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesCore")

	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.0")
	implementation("com.github.f4b6a3:ulid-creator:5.2.3")
	implementation("org.springframework.security:spring-security-crypto:$springCrypto")
	implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
	testImplementation("io.mockk:mockk:1.13.17")

	implementation("org.bytedeco:javacv:1.5.11")
	runtimeOnly("org.bytedeco:ffmpeg:7.1-1.5.11:linux-x86_64")
	runtimeOnly("org.bytedeco:ffmpeg:7.1-1.5.11:linux-arm64")
	runtimeOnly("org.bytedeco:opencv:4.10.0-1.5.11:linux-x86_64")
	runtimeOnly("org.bytedeco:opencv:4.10.0-1.5.11:linux-arm64")

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
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
