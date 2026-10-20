import java.util.jar.JarFile
import org.gradle.api.artifacts.ExternalModuleDependency

fun setting(name: String) = project.property(name).toString()
val loader = setting("loader")
val minecraft = setting("minecraft_version")
val mappedFabric = loader == "fabric" && !minecraft.startsWith("26.")
require(project.hasProperty("kuiJar")) { "This target requires -PkuiJar=<matching KUI JAR with the basic chunk rendering API>" }
val localKuiJar = file(project.property("kuiJar"))
require(localKuiJar.isFile) { "KUI JAR does not exist: $localKuiJar" }
var kuiVersion = ""
JarFile(localKuiJar).use { archive ->
    val required = mutableListOf(
            "io/github/kltyton/kltytonui/chunkmap/KuiNativeTerrainRenderer.class",
            "io/github/kltyton/kltytonui/chunkmap/KuiNativeTerrainRenderer\$Draw.class",
            "io/github/kltyton/kltytonui/chunkmap/KuiNativeTerrainRenderer\$GpuMesh.class",
            "io/github/kltyton/kltytonui/chunkmap/KuiNativeMesh.class")
    if (loader == "fabric" && !mappedFabric) {
        required += "io/github/kltyton/kltytonui/client/gui/KuiNativeViewport.class"
    } else required += "io/github/kltyton/kltytonui/chunkmap/KuiChunkMeshBaker.class"
    if (minecraft != "1.18.2" && minecraft != "1.19.2" && minecraft != "1.20.1") {
        if (loader != "fabric" || mappedFabric) required += "io/github/kltyton/kltytonui/chunkmap/ChunkMapSnapshot.class"
        required += "assets/kltytonui/kltytonui/kltytonui/runtime/mcui/mcui-oreui.kui.js"
    }
    required.forEach { require(archive.getEntry(it) != null) { "Missing KUI chunk rendering/component API: $it" } }
    if (loader == "fabric") {
        val metadata = archive.getEntry("fabric.mod.json") ?: error("Missing Fabric KUI metadata")
        archive.getInputStream(metadata).use {
            kuiVersion = groovy.json.JsonSlurper().parse(it).let { value -> (value as Map<*, *>)["version"].toString() }
        }
    }
    if (loader == "neoforge") require(
            archive.manifest?.mainAttributes?.getValue("Automatic-Module-Name") != null || archive.getEntry("module-info.class") != null
    ) { "The KUI JAR must declare Automatic-Module-Name or module-info.class for local jar-in-jar packaging" }
}
repositories {
    maven("https://chocolateminecraft.com/maven") {
        content { includeGroup("xaero.map"); includeGroup("xaero.lib") }
    }
    maven(if (minecraft == "1.18.2") "https://api.modrinth.com/maven" else "https://maven.latvian.dev/releases")
    flatDir { dirs(localKuiJar.parentFile) }
}
dependencies {
    val xaero = "xaero.map:xaeroworldmap-$loader-${setting("xaero_mc_version")}:${setting("xaero_version")}" +
            if (loader == "forge") ":dev" else ""
    add(if (mappedFabric) "modImplementation" else "implementation", xaero)
    add(if (mappedFabric) "modCompileOnly" else "compileOnly", files(localKuiJar))
    add(if (mappedFabric) "modRuntimeOnly" else "runtimeOnly", files(localKuiJar))
    if (loader == "fabric") {
        add("include", mapOf("group" to "io.github.kltyton.kltytonui", "name" to localKuiJar.name.removeSuffix(".jar"), "version" to kuiVersion))
        val rhino = create(setting("rhino_dependency"))
        add(if (mappedFabric) "modImplementation" else "implementation", rhino)
        add("include", rhino)
    } else {
        add("jarJar", files(localKuiJar))
        val rhino = create(setting("rhino_dependency")) as ExternalModuleDependency
        val version = setting("rhino_dependency").substringAfterLast(':')
        rhino.version { strictly("[$version]"); prefer(version) }
        add("implementation", rhino)
        add("jarJar", rhino)
        if (loader == "forge") {
            if (minecraft == "1.18.2" || minecraft == "1.19.2") add("compileOnly", "org.joml:joml:1.10.5")
            add("annotationProcessor", "org.spongepowered:mixin:0.8.5:processor")
            add("compileOnly", "io.github.llamalad7:mixinextras-common:0.4.1")
            add("annotationProcessor", "io.github.llamalad7:mixinextras-common:0.4.1")
            val extras = create("io.github.llamalad7:mixinextras-forge:0.4.1") as ExternalModuleDependency
            extras.version { strictly("[0.4.1,)"); prefer("0.4.1") }
            add("implementation", extras)
            add("jarJar", extras)
        }
    }
}
if (loader == "forge") tasks.named<Jar>("jar") { manifest.attributes("MixinConfigs" to "xaeroearth.mixins.json") }
