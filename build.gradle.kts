plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("kapt") version "1.9.21"
    id("maven-publish")
    id("signing")
}

group = "com.sentralyx.kddm"
version = "2.5.54"

kapt {
    annotationProcessor( "${group}.processors.DatabaseEntityProcessor")
}

java {
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")

    implementation(kotlin("stdlib"))

    kapt("com.squareup:kotlinpoet:1.11.0")
    implementation("com.squareup:kotlinpoet:1.11.0")

    implementation("org.apache.commons:commons-dbcp2:2.1.1")
    implementation("mysql:mysql-connector-java:8.0.30")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.1.3")
}

kotlin {
    jvmToolchain(8)
}

tasks.register("generateVersionProperties") {
    doLast {
        val resourcesDir = file("src/main/resources")
        val propertiesFile = File(resourcesDir, "version.properties")

        resourcesDir.mkdirs()
        propertiesFile.writeText("version=$version")
    }
}

tasks.named("processResources") {
    dependsOn("generateVersionProperties")
}

sourceSets.main {
    resources.srcDir("${buildDir}/generated/resources") // Include generated resources
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])

            groupId = "com.sentralyx"
            artifactId = "kddm"
            version = version

            artifact(tasks["javadocJar"])
            artifact(tasks["sourcesJar"])
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/NebraskyTheWolf/kDDM")
            credentials {
                username = project.findProperty("github.actor") as String? ?: ""
                password = project.findProperty("github.token") as String? ?: ""
            }
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["maven"])
}
