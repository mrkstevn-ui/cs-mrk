rootProject.name = "CloudstreamTR"

// Otomatik olarak build.gradle.kts içeren tüm alt klasörleri modül olarak dahil eder
File(rootDir, ".").eachDir { dir ->
    if (File(dir, "build.gradle.kts").exists() && dir.name != "build") {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
