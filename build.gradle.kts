import org.gradle.api.GradleException
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.security.MessageDigest

plugins {
    `java-library`
    jacoco
    `maven-publish`
    signing
}

group = "name.jurgenei"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "xml-sax-sexpr"

            pom {
                name.set("xml-sax-sexpr")
                description.set("SAX parser/serializer and XMLReader for bracket-based XML/XDM S-expressions")
                url.set("https://github.com/jurgenei/xml-sax-sexpr")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }

                developers {
                    developer {
                        id.set("jurgenei")
                        name.set("Jurgen Hildebrand")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/jurgenei/xml-sax-sexpr.git")
                    developerConnection.set("scm:git:ssh://git@github.com/jurgenei/xml-sax-sexpr.git")
                    url.set("https://github.com/jurgenei/xml-sax-sexpr")
                }
            }
        }
    }

    repositories {
        mavenLocal()
        maven {
            name = "sonatype"
            val releasesRepoUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            credentials {
                username = providers.gradleProperty("mavenCentralUsername").orNull
                password = providers.gradleProperty("mavenCentralPassword").orNull
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey").orNull
    val signingPassword = providers.gradleProperty("signingPassword").orNull
    val signingKeyId = providers.gradleProperty("signingKeyId").orNull

    if (!signingKey.isNullOrEmpty() && !signingPassword.isNullOrEmpty()) {
        if (!signingKeyId.isNullOrEmpty()) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        } else {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
    } else {
        useGpgCmd()
    }

    sign(publishing.publications)
}


tasks.withType<Test>().configureEach {
    useJUnit()
}

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

extensions.configure<JacocoPluginExtension> {
    toolVersion = "0.8.12"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.00".toBigDecimal()
            }
        }
    }
}

tasks.register("coverage") {
    group = "verification"
    description = "Runs tests, generates JaCoCo report, and verifies minimum coverage threshold."
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

val stageCentralBundleRepo by tasks.registering(Sync::class) {
    dependsOn(tasks.named("publishMavenJavaPublicationToMavenLocal"))

    val artifactBaseDir = file("${System.getProperty("user.home")}/.m2/repository/name/jurgenei/xml-sax-sexpr")
    val artifactVersionDir = file("$artifactBaseDir/${project.version}")
    from(artifactVersionDir)
    into(layout.buildDirectory.dir("central-staging-repo/name/jurgenei/xml-sax-sexpr/${project.version}"))

    doFirst {
        if (!artifactVersionDir.exists()) {
            throw GradleException("Expected local Maven artifact version directory not found: $artifactVersionDir")
        }
    }
}

val generateCentralBundleChecksums by tasks.registering {
    dependsOn(stageCentralBundleRepo)
    notCompatibleWithConfigurationCache("Generates checksum files by scanning staged output directory at execution time.")

    doLast {
        val stagedVersionDir = layout.buildDirectory
            .dir("central-staging-repo/name/jurgenei/xml-sax-sexpr/${project.version}")
            .get()
            .asFile

        if (!stagedVersionDir.exists()) {
            throw GradleException("Expected staged version directory not found: $stagedVersionDir")
        }

        fun checksum(file: java.io.File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        stagedVersionDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".md5") && !it.name.endsWith(".sha1") }
            .forEach { file ->
                file.resolveSibling("${file.name}.md5").writeText("${checksum(file, "MD5")}\n")
                file.resolveSibling("${file.name}.sha1").writeText("${checksum(file, "SHA-1")}\n")
            }
    }
}

tasks.register<Zip>("packageCentralBundle") {
    dependsOn(generateCentralBundleChecksums)
    archiveBaseName.set("xml-sax-sexpr")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("central-bundle")
    destinationDirectory.set(layout.buildDirectory.dir("central-bundle"))
    from(layout.buildDirectory.dir("central-staging-repo"))
}

