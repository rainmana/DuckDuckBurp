plugins {
    id("java")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.2")
    implementation("org.duckdb:duckdb_jdbc:1.2.1")
    implementation("com.vladsch.flexmark:flexmark:0.64.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    archiveFileName.set("DuckDuckBurp.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().filter { it.isDirectory })
    from(configurations.runtimeClasspath.get().filterNot { it.isDirectory }.map { zipTree(it) })
    finalizedBy("reloadJar")
}

tasks.register<Copy>("reloadJar") {
    dependsOn(tasks.named("jar"))
    from(tasks.named<Jar>("jar").get().archiveFile)
    into(layout.buildDirectory.dir("burp"))
}