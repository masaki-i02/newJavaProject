plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "jp.co.sample"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Spring Boot 4.1 が管理する Testcontainers は 2.0 系だが、
// junit-jupiter / postgresql モジュールの座標が 1.x から変わっており解決できない。
// 設計書の DDL を検証したのと同じ構成で動かしたいので 1.x に固定する。
val testcontainersVersion = "1.21.3"
val archUnitVersion = "1.4.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

	// 統合テストは実物の PostgreSQL に対して行う。
	// H2 では EXCLUDE 制約・パーティション・配列型・生成列が再現できない（ADR / CLAUDE.md 落とし穴 6）
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")

	// 層とコンテキストの依存方向を強制する（AR-01〜AR-09）
	testImplementation("com.tngtech.archunit:archunit-junit5:$archUnitVersion")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

tasks.withType<Test> {
	useJUnitPlatform()
	defaultCharacterEncoding = "UTF-8"
	// ドメインは壁掛け時計時刻を扱う。JVM のタイムゾーンがずれるとテストの意味が変わる
	systemProperty("user.timezone", "Asia/Tokyo")
	// Docker が使えない環境では、外部の PostgreSQL を指定して実行できるようにする
	//   ./gradlew test -Dkintai.test.datasource.url=jdbc:postgresql://localhost:5432/kintai_test
	listOf("kintai.test.datasource.url",
	       "kintai.test.datasource.username",
	       "kintai.test.datasource.password").forEach { key ->
		System.getProperty(key)?.let { systemProperty(key, it) }
	}
	testLogging {
		events("failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
	}
}
