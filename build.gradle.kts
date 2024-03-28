plugins {
    java
    signing
    id("org.embulk.embulk-plugins") version("0.6.2")
    id("com.diffplug.spotless") version( "6.25.0")
    id("cl.franciscosolis.sonatype-central-upload") version("1.0.3")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withJavadocJar()
    withSourcesJar()
}

group = "com.reproio.embulk"
version = "0.1.0"
description = "Trino JDBC input plugin for Embulk loads records from Trino"
val projectUrl = "https://github.com/reproio/embulk-input-trino-jdbc"

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
            description = project.description

            from(components["java"])

            pom {
                packaging = "jar"

                name = project.name
                description = project.description
                url = projectUrl

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
                    url = projectUrl
                }
                developers {
                    developer {
                         name = "Kenji Okimoto"
                         email = "kenji.okimoto@repro.io"
                    }
                }
            }
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["maven"])
}

tasks.withType<Sign>().configureEach() {
    onlyIf { System.getenv()["SKIP_SIGNING"] == null }
}

// TODO Use official Gradle plugin or something to upload artifacts to sonatype central portal.
// See https://central.sonatype.org/publish/publish-portal-gradle/
tasks.sonatypeCentralUpload {
    dependsOn("jar", "sourcesJar", "javadocJar", "generatePomFileForMavenPublication")
    username = System.getenv("SONATYPE_CENTRAL_USERNAME")
    password = System.getenv("SONATYPE_CENTRAL_PASSWORD")

    archives = files(
        tasks.named("jar"),
        tasks.named("sourcesJar"),
        tasks.named("javadocJar"),
    )
    pom = file(tasks.named("generatePomFileForMavenPublication").get().outputs.files.single())

    signingKey = System.getenv("PGP_SIGNING_KEY")
    signingKeyPassphrase = System.getenv("PGP_SIGNING_KEY_PASSPHRASE")

    publishingType = System.getenv().getOrDefault("SONATYPE_CENTRAL_PUBLISHING_TYPE", "MANUAL")
}

tasks.gem {
    from("LICENSE.txt")
    setProperty("authors", listOf(""))
    setProperty("email", listOf(""))
    setProperty("description", project.description.toString())
    setProperty("summary", project.description.toString())
    setProperty("homepage", projectUrl)
    setProperty("licenses", listOf("Apache-2.0"))
}

tasks.gemPush {
    setProperty("host", "https://rubygems.org")
}

tasks.build {
    dependsOn(tasks.gem)
}

tasks.publish {
    // TODO Add tasks.sonatypeCentralUpload
    dependsOn(tasks.gemPush)
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
