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
val ecjVersion = "3.40.0"

/**
 * Eclipse のコンパイラを解決するためだけの構成。
 * アプリケーションの classpath には載せない。
 */
val ecj: Configuration = configurations.create("ecj")

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

	// ★ Eclipse のコンパイラ（ecj）。コンパイルには使わず、検査にだけ使う（ecjCheck）
	ecj("org.eclipse.jdt:ecj:$ecjVersion")
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
	// 非推奨 API を使っている箇所を具体的に知る。Note だけでは場所が分からない
	options.compilerArgs.add("-Xlint:deprecation")
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

/**
 * <strong>Eclipse のコンパイラ（ecj）でも通ることを確かめる。</strong>
 *
 * javac と ecj は型推論の実装が違うので、<strong>javac が通すコードを ecj が拒む</strong>
 * ことがある。実際 {@code extracting(Object::getClass).containsExactly(A.class, …)} は
 * javac では通り、Eclipse では「capture#23 に適用できません」で赤くなっていた。
 * 開発を Eclipse で行う以上、javac だけを見ているとこの種の食い違いに気づけない
 * （CLAUDE.md 落とし穴 181）。
 *
 * ★ クラスファイルは書き出さない（-d none）。ここでやりたいのは検査だけである。
 */
tasks.register<JavaExec>("ecjCheck") {
	group = "verification"
	description = "Eclipse のコンパイラでも main / test がコンパイルできることを確かめる"
	dependsOn(tasks.named("compileTestJava"))

	classpath = ecj
	mainClass = "org.eclipse.jdt.internal.compiler.batch.Main"

	val sourceDirs = sourceSets["main"].java.srcDirs + sourceSets["test"].java.srcDirs
	val compileClasspath = sourceSets["test"].compileClasspath

	argumentProviders.add(CommandLineArgumentProvider {
		listOf("-21", "-encoding", "UTF-8", "-proc:none", "-nowarn", "-d", "none",
				"-classpath", compileClasspath.asPath)
			.plus(sourceDirs.filter { it.exists() }.map { it.absolutePath })
	})
}
