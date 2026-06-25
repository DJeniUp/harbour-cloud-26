plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "space.harbour"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// --- Homework 4: Temporal OrderWorkflow ---
	implementation("io.temporal:temporal-sdk:1.35.0")
	implementation("com.fasterxml.jackson.core:jackson-databind")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	testImplementation("io.temporal:temporal-testing:1.35.0")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// --- Homework 4: run the Temporal worker and the order Starter CLI ---
tasks.register<JavaExec>("runWorker") {
	group = "application"
	description = "Runs the Temporal worker that hosts OrderWorkflow + activities."
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass = "space.harbour.cloud.orders.Worker"
	systemProperty("logback.configurationFile", "config/logback-worker.xml")
}

tasks.register<JavaExec>("runStarter") {
	group = "application"
	description = "CLI to start an order and send signals. Pass args via -Pargs=\"start F1\"."
	classpath = sourceSets.main.get().runtimeClasspath
	mainClass = "space.harbour.cloud.orders.Starter"
	systemProperty("logback.configurationFile", "config/logback-worker.xml")
	(project.findProperty("args") as String?)?.let { args(it.split(" ")) }
}
