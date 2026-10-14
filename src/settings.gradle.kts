import groovy.json.JsonSlurper

rootProject.name = "EnterpriseAI"
val moduleFolders = file("modules").listFiles()?.filter { it.isDirectory && File(it,"module.json").isFile }?.sortedBy { it.name } ?: emptyList()
val ids = mutableSetOf<String>()
moduleFolders.forEach {
    val manifest = JsonSlurper().parse(File(it,"module.json")) as Map<*,*>
    val id = manifest["id"] as? String ?: error("Missing module ID in ${it.name}")
    require(id.matches(Regex("[a-z][a-z0-9-]*")) && ids.add(id)) { "Invalid or duplicate module ID: $id" }
    val version = manifest["contractVersion"]?.toString() ?: "1.0"
    require(version.startsWith("1.")) { "Incompatible module contract: $id $version" }
}
gradle.extra["moduleFolders"] = moduleFolders.map { it.name }
