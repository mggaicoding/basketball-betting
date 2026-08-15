plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.0.4"
	id("io.spring.dependency-management") version "1.1.7"
	application
}

group = "com.hkjc.training"
version = "0.0.1-SNAPSHOT"
description = "Full-day basketball betting training repository"

extra["springCloudVersion"] = "2025.1.1"

springBoot {
	mainClass = "com.hkjc.training.betting.BasketballBettingDemoApplicationKt"
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
	// Spring MVC needs this to invoke a suspend handler method; without it the request
	// fails with NoClassDefFoundError: org/reactivestreams/Publisher.
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
	// MDCContext keeps the trace identifier alive across coroutine thread switches.
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.10.2")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// Serves /v3/api-docs and /swagger-ui.html. The 3.x line targets Spring Boot 4.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	// Without a registry Micrometer collects nothing to scrape and /actuator/prometheus 404s.
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	// Flyway 10 split per-database support out of flyway-core; without this module the
	// `database` profile fails at startup with "Unsupported Database: PostgreSQL".
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
	testImplementation("io.kotest:kotest-runner-junit5:6.2.3")
	testImplementation("io.kotest:kotest-assertions-core:6.2.3")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
	testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
	mainClass = "com.hkjc.training.betting.BasketballBettingDemoApplicationKt"
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
	}
}

/**
 * Classroom tooling lives outside the production source set so it never ships in the artifact,
 * but it still runs through Gradle. It sees main on its classpath, not the other way round.
 */
val demoSourceSet =
	sourceSets.create("demo") {
		compileClasspath += sourceSets.main.get().output
		runtimeClasspath += sourceSets.main.get().output
	}

configurations[demoSourceSet.implementationConfigurationName]
	.extendsFrom(configurations.implementation.get())
configurations[demoSourceSet.runtimeOnlyConfigurationName]
	.extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("runDemoDownstreams") {
	group = "training"
	description = "Starts the prepared external Odds and Risk HTTP services for the coroutine demo"
	classpath = demoSourceSet.runtimeClasspath
	mainClass = "com.hkjc.training.betting.demo.DemoDownstreamServicesKt"
}

val integrationTestSourceSet =
	sourceSets.create("integrationTest") {
		// A new source set does not inherit the main output, so @SpringBootTest would fail
		// with "Unable to find a @SpringBootConfiguration" at runtime.
		compileClasspath += sourceSets.main.get().output
		runtimeClasspath += sourceSets.main.get().output
	}

configurations[integrationTestSourceSet.implementationConfigurationName]
	.extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
	.extendsFrom(configurations.testRuntimeOnly.get())

val integrationTest by tasks.registering(Test::class) {
	description = "Runs PostgreSQL/Flyway integration tests; Docker is required"
	group = "verification"
	testClassesDirs = integrationTestSourceSet.output.classesDirs
	classpath = integrationTestSourceSet.runtimeClasspath
	useJUnitPlatform()
	shouldRunAfter(tasks.test)
}

tasks.check {
	// CI may add integrationTest when Docker is available; fast local check stays broker/database independent.
}

val thinJar by tasks.registering(Jar::class) {
	archiveFileName = "basketball-betting.jar"
	from(sourceSets.main.get().output)
}

tasks.register<Sync>("slimDist") {
	description = "Builds app/, lib/, and bin/ as the course slim distribution"
	group = "distribution"
	dependsOn(thinJar)
	into(layout.buildDirectory.dir("slim/basketball-betting"))
	from(thinJar) { into("app") }
	from(configurations.runtimeClasspath) { into("lib") }
	from("src/distribution/bin") { into("bin") }
}
