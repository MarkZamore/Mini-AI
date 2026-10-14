import groovy.json.JsonSlurper
import java.io.File

plugins { java; application }
group = "ai.enterprise"
version = "1.0.0"
repositories { mavenCentral() }
dependencies {
    implementation(files("build/dependencies/swt-3.135.0-patched.jar"))
    implementation("org.graalvm.sdk:nativeimage:25.0.2")
}
java { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
application { mainClass = "ai.enterprise.bootstrap.Main" }
val moduleFolders = (gradle.extra["moduleFolders"] as List<*>).map { file("modules/$it") }
val manifests = moduleFolders.map { JsonSlurper().parse(File(it,"module.json")) as Map<String,Any> }
dependencies {
    manifests.forEach { manifest -> (manifest["dependencies"] as? List<*>).orEmpty().forEach { coordinate ->
        val value=coordinate.toString()
        require(value.matches(Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)?"))) { "Use pinned dependency coordinates in ${manifest["id"]}" }
        implementation(value)
    } }
}
val generated = layout.buildDirectory.dir("generated/java")
val legalResources=layout.buildDirectory.dir("generated/legal-resources")
val generateLegalResources by tasks.registering(Exec::class) {
    val sharedBuild=file(System.getenv("ENTERPRISE_BUILD_SHARED_ROOT") ?: "build")
    val interpreter=System.getenv("ENTERPRISE_BUILD_PYTHON") ?: "python.exe"
    commandLine(interpreter,"toolchain/package_licenses.py")
    inputs.files(fileTree("toolchain"),fileTree("patches"),fileTree("licenses"),fileTree("build-logic"),file("build.gradle.kts"),file("build.ps1"),moduleFolders.map { File(it,"module.json") })
    inputs.files(fileTree("core"),fileTree("gradle"),file("settings.gradle.kts"),file("gradle.properties"),file("gradlew"),file("gradlew.bat"))
    moduleFolders.forEach { folder -> inputs.files(fileTree(folder) { exclude("build/**","**/__pycache__/**") }) }
    inputs.files(fileTree(File(sharedBuild,"dependencies")) { include("swt-*.jar","EPL-2.0.txt") },fileTree(File(sharedBuild,"static-toolchain/downloads")) { include("*.zip") },fileTree(File(sharedBuild,"vendor")) { include("*sqlite*.zip") },fileTree(File(sharedBuild,"tools/graalvm-community-openjdk-25.0.2+10.1/legal")))
    inputs.files(listOf("LICENSE.txt","LICENSE_NATIVEIMAGE.txt","THIRD_PARTY_LICENSE.txt","GRAALVM-README.md").map { File(sharedBuild,"tools/graalvm-community-openjdk-25.0.2+10.1/$it") })
    outputs.dir(legalResources)
    onlyIf { file("toolchain/package_licenses.py").isFile }
}
tasks.processResources { dependsOn(generateLegalResources) }
sourceSets.main {
    java.setSrcDirs(listOf("core/contracts","core/bootstrap","core/shell","core/platform-windows",generated.get().asFile)+moduleFolders.flatMap { listOf(File(it,"service"),File(it,"ui")) })
    resources.setSrcDirs(file("core").listFiles()?.filter { it.isDirectory }?.map { File(it,"resources") }.orEmpty()+moduleFolders.map { File(it,"resources") }+legalResources.get().asFile)
    java.exclude("**/native/**","**/resources/**")
}
val moduleIds=manifests.map { it["id"].toString() }.toSet()
fun testsEnabled(folder:File):Boolean {
    val metadata=File(folder,"test.json")
    if(!metadata.isFile)return true
    val config=JsonSlurper().parse(metadata) as Map<*,*>
    return (config["requiresModules"] as? List<*>).orEmpty().all { it.toString() in moduleIds }
}
sourceSets.test {
    java.setSrcDirs(file("tests").listFiles()?.filter { it.isDirectory && it.name !in listOf("portability") && testsEnabled(it) }.orEmpty()+moduleFolders.map { File(it,"tests") }.filter { testsEnabled(it) })
    resources.setSrcDirs(emptyList<String>())
}

val generateCatalog by tasks.registering {
    inputs.files(moduleFolders.map { File(it,"module.json") }); outputs.dir(generated)
    doLast {
        val providers = mutableMapOf<String,String>()
        manifests.forEach { m -> providers[m["id"].toString()]=m["id"].toString(); (m["provides"] as? List<*>)?.forEach { p -> require(providers.put(p.toString(),m["id"].toString())==null) { "Duplicate provider $p" } } }
        val remaining=manifests.associateBy { it["id"].toString() }.toMutableMap()
        val ordered=mutableListOf<Map<String,Any>>()
        while(remaining.isNotEmpty()) {
            val ready=remaining.values.filter { m -> (m["requires"] as? List<*>).orEmpty().none { providers[it.toString()] in remaining.keys } }.sortedBy { it["id"].toString() }
            require(ready.isNotEmpty()) { "Module dependency cycle: ${remaining.keys}" }
            ready.forEach { remaining.remove(it["id"].toString()); ordered.add(it) }
        }
        val folder=generated.get().file("ai/enterprise/generated").asFile; folder.mkdirs()
        val services=ordered.mapNotNull { (it["serviceFactory"]?:it["factory"]) as? String }.joinToString(",") { "new $it()" }
        val panels=ordered.mapNotNull { it["uiFactory"] as? String }.joinToString(",") { "new $it()" }
        File(folder,"GeneratedCatalog.java").writeText("""
            package ai.enterprise.generated;
            public final class GeneratedCatalog {
              private GeneratedCatalog() {}
              public static java.util.List<ai.enterprise.contracts.Api.ModuleFactory> modules() { return java.util.List.of($services); }
              public static java.util.List<ai.enterprise.shell.PanelFactory> panels() { return java.util.List.of($panels); }
            }
        """.trimIndent())
        file("build/generated/module-inventory.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(ordered)))
    }
}
tasks.compileJava { dependsOn(generateCatalog); options.encoding="UTF-8" }
tasks.jar { manifest { attributes("Main-Class" to application.mainClass.get(),"Implementation-Version" to version) } }
tasks.clean { setDelete(setOf<Any>(file("build/classes"),file("build/generated"),file("build/libs"),file("build/modules"),file("build/resources"),file("build/tmp"),file("build/staging"),file("build/native-image"),file("build/native-metadata"),file("build/runtime-libs"))) }
val copyRuntime by tasks.registering(Sync::class) { from(configurations.runtimeClasspath); into("build/runtime-libs") }
val buildPowerShell=System.getenv("ENTERPRISE_BUILD_POWERSHELL") ?: "powershell.exe"
val nativeLibraries by tasks.registering(Exec::class) { commandLine(buildPowerShell,"-NoProfile","-ExecutionPolicy","Bypass","-File","build.ps1","-Action","native-libraries") }
tasks.register<Exec>("nativeImage") { dependsOn(tasks.jar,copyRuntime,nativeLibraries); commandLine(buildPowerShell,"-NoProfile","-ExecutionPolicy","Bypass","-File","build.ps1","-Action","native-image") }
tasks.register<Exec>("staticImage") { dependsOn(tasks.jar,copyRuntime); commandLine(buildPowerShell,"-NoProfile","-ExecutionPolicy","Bypass","-File","toolchain/build_static_application.ps1","-SkipJavaBuild") }
tasks.register<Exec>("appImage") { dependsOn(tasks.jar,copyRuntime,nativeLibraries); commandLine(buildPowerShell,"-NoProfile","-ExecutionPolicy","Bypass","-File","build.ps1","-Action","app-image") }
tasks.register<Exec>("publishPortable") { commandLine(buildPowerShell,"-NoProfile","-ExecutionPolicy","Bypass","-File","build.ps1","-Action","publish") }
val moduleJars=moduleFolders.map { folder ->
    val manifest=JsonSlurper().parse(File(folder,"module.json")) as Map<*,*>
    val id=manifest["id"].toString()
    tasks.register<Jar>("${id.replace('-','_')}ModuleJar") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        dependsOn(tasks.classes); archiveBaseName.set("enterpriseai-$id"); destinationDirectory.set(layout.buildDirectory.dir("modules"))
        val packages=fileTree(folder).matching { include("service/**/*.java","ui/**/*.java") }.files.mapNotNull { Regex("package\\s+([a-zA-Z0-9_.]+)\\s*;").find(it.readText())?.groupValues?.get(1)?.replace('.','/') }.distinct()
        from(sourceSets.main.get().output) { packages.forEach { include("$it/**") } }
        from(File(folder,"module.json"))
        from(File(folder,"resources"))
    }
}
tasks.register("moduleArtifacts") { dependsOn(moduleJars) }
tasks.register<Jar>("coreArtifact") { dependsOn(tasks.classes); archiveBaseName.set("enterpriseai-core"); destinationDirectory.set(layout.buildDirectory.dir("modules")); from(sourceSets.main.get().output) { include("ai/enterprise/contracts/**","ai/enterprise/bootstrap/EnterpriseAIComponent*","ai/enterprise/shell/**","ai/enterprise/platform/**") } }
tasks.register<Jar>("contractsArtifact") { dependsOn(tasks.classes); archiveBaseName.set("enterpriseai-contracts"); destinationDirectory.set(layout.buildDirectory.dir("modules")); from(sourceSets.main.get().output) { include("ai/enterprise/contracts/**") } }
val splitModuleJars=moduleFolders.flatMap { folder ->
    val id=(JsonSlurper().parse(File(folder,"module.json")) as Map<*,*>)["id"].toString()
    listOf("service","ui").filter { File(folder,it).isDirectory }.map { kind ->
        tasks.register<Jar>("${id.replace('-','_')}${kind.replaceFirstChar { it.uppercase() }}Jar") {
            dependsOn(tasks.classes); archiveBaseName.set("enterpriseai-$id-$kind"); destinationDirectory.set(layout.buildDirectory.dir("modules")); duplicatesStrategy=DuplicatesStrategy.EXCLUDE
            val patterns=fileTree(File(folder,kind)).matching { include("**/*.java") }.files.mapNotNull { source -> Regex("package\\s+([a-zA-Z0-9_.]+)\\s*;").find(source.readText())?.groupValues?.get(1)?.replace('.','/')?.let { "$it/${source.nameWithoutExtension}" } }
            from(sourceSets.main.get().output.classesDirs) { patterns.forEach { include("$it.class","$it\$*.class") } }
            if(kind=="service")from(File(folder,"resources"))
            from(File(folder,"module.json"))
        }
    }
}
tasks.named("moduleArtifacts") { dependsOn(splitModuleJars,"contractsArtifact") }
tasks.wrapper {
    gradleVersion="9.8.1"
    distributionType=Wrapper.DistributionType.BIN
    distributionSha256Sum="dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"
    distributionBase=Wrapper.PathBase.PROJECT
    distributionPath="build/wrapper/dists"
    archiveBase=Wrapper.PathBase.PROJECT
    archivePath="build/wrapper/dists"
}
