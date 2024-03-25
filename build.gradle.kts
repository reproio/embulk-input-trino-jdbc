plugins {
    java
    id("org.embulk.embulk-plugins") version("0.6.2")
    id("com.diffplug.spotless") version( "6.25.0")

}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

group = "com.reproio.embulk"
version = "0.1.0"
description = "Embulk input plugin for Trino JDBC"

embulkPlugin {
    mainClass = "com.reproio.embulk.input.trino.TrinoJdbcInputPlugin"
    category = "input"
    type = "trino-jdbc"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.embulk:embulk-spi:0.11")
    compileOnly("org.embulk:embulk-api:0.10.43")
    implementation("org.embulk:embulk-input-jdbc:0.13.2")
    implementation("io.trino:trino-jdbc:442")
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    java {
        importOrder()
        removeUnusedImports()
        googleJavaFormat()
    }
}

tasks.compileJava {
    dependsOn(tasks.spotlessApply)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])

            pom {
                packaging = "jar"

                name = project.name
                description = project.description

                licenses {
                    license {
                        // http://central.sonatype.org/pages/requirements.html#license-information
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/reproio/embulk-input-trino-jdbc.git"
                    developerConnection = "scm:git:git@github.com:reproio/embulk-input-trino-jdbc.git"
                    url = "https://github.com/reproio/embulk-input-trino-jdbc"
                }
            }
        }
    }
}

tasks.gem {
    from("LICENSE.txt")
    setProperty("authors", listOf(""))
    setProperty("email", listOf(""))
    setProperty("description", "Trino JDBC input plugin for Embulk")
    setProperty("summary", "Trino JDBC input plugin for Embulk")
    setProperty("homepage", "https://github.com/reproio/embulk-input-trino-jdbc")
    setProperty("licenses", listOf("Apache-2.0"))
}

tasks.gemPush {
    setProperty("host", "https://rubygems.org")
}

// For local testing
tasks.register("cacheToMavenLocal", Sync::class) {
    from(File(gradle.gradleUserHomeDir, "caches/modules-2/files-2.1"))
    into(repositories.mavenLocal().url)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    eachFile {
        val parts: List<String> = path.split("/")
        path = listOf(parts[0].replace(".", "/"), parts[1], parts[2], parts[4]).joinToString("/")
    }
    includeEmptyDirs = false
}
