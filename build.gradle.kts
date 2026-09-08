import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.plugins.signing.Sign
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

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
                username = providers.gradleProperty("ossrhUsername")
                    .orElse(providers.gradleProperty("mavenCentralUsername"))
                    .orNull
                password = providers.gradleProperty("ossrhPassword")
                    .orElse(providers.gradleProperty("mavenCentralPassword"))
                    .orNull
            }
        }
    }
}

signing {
    val signingKey = providers.gradleProperty("signingKey").orNull
    val signingPassword = providers.gradleProperty("signingPassword").orNull
    val signingKeyId = providers.gradleProperty("signingKeyId").orNull

    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        if (!signingKeyId.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        } else {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
    } else {
        useGpgCmd()
    }

    sign(publishing.publications)
}

tasks.withType<Sign>().configureEach {
    onlyIf {
        gradle.taskGraph.allTasks.any { task ->
            task.name.startsWith("publish") || task.name.startsWith("sign")
        }
    }
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

