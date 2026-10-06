package com.zain.holoos.fs

data class FsNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

interface FileSource {
    fun root(): FsNode
    /** Folders first, then files; hidden entries skipped; capped for frame-rate safety. */
    fun list(node: FsNode): List<FsNode>
}

expect fun platformFileSource(): FileSource

enum class FileKind { Folder, Image, Video, Audio, Code, Document, Archive, Other }

fun FsNode.kind(): FileKind = when {
    isDirectory -> FileKind.Folder
    extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg") -> FileKind.Image
    extension in setOf("mp4", "mkv", "mov", "avi", "webm") -> FileKind.Video
    extension in setOf("mp3", "wav", "flac", "ogg", "m4a") -> FileKind.Audio
    extension in setOf("kt", "kts", "java", "py", "js", "ts", "html", "css", "json", "xml", "c", "cpp", "rs", "go", "gradle", "md") -> FileKind.Code
    extension in setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv") -> FileKind.Document
    extension in setOf("zip", "rar", "7z", "tar", "gz", "apk", "exe", "msi") -> FileKind.Archive
    else -> FileKind.Other
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "${(b * 10 / (1L shl 30)) / 10.0} GB"
    b >= 1L shl 20 -> "${b / (1L shl 20)} MB"
    b >= 1L shl 10 -> "${b / (1L shl 10)} KB"
    else -> "$b B"
}

/** A believable virtual drive, used on Android and as a fallback anywhere. */
class DemoFileSource : FileSource {
    private val tree: Map<String, List<FsNode>> = buildMap {
        fun dir(parent: String, vararg names: String) =
            names.map { FsNode(it, "$parent/$it", true) }
        fun files(parent: String, vararg names: Pair<String, Long>) =
            names.map { (n, s) -> FsNode(n, "$parent/$n", false, s) }

        put("/", dir("", "Projects", "Suits", "Media", "Research", "Documents"))
        put("/Projects", dir("/Projects", "HoloOS", "ArcReactor", "Satellites") +
            files("/Projects", "roadmap.md" to 4_200, "budget.xlsx" to 88_000))
        put("/Projects/HoloOS", dir("/Projects/HoloOS", "shared", "androidApp", "tracker") +
            files("/Projects/HoloOS", "build.gradle.kts" to 1_100, "README.md" to 3_400))
        put("/Projects/HoloOS/shared", files("/Projects/HoloOS/shared", "Holo.kt" to 5_200, "Navigator.kt" to 4_100, "Files.kt" to 9_800))
        put("/Projects/ArcReactor", dir("/Projects/ArcReactor", "palladium-core", "vibranium-core") +
            files("/Projects/ArcReactor", "output-log.csv" to 2_400_000, "schematic.pdf" to 6_100_000))
        put("/Projects/Satellites", files("/Projects/Satellites", "orbit-sim.py" to 22_000, "telemetry.json" to 950_000))
        put("/Suits", dir("/Suits", "Mark I", "Mark II", "Mark III", "Mark VII", "Mark L") +
            files("/Suits", "suit-index.json" to 12_000))
        listOf("Mark I", "Mark II", "Mark III", "Mark VII", "Mark L").forEach { m ->
            put("/Suits/$m", dir("/Suits/$m", "Helmet", "Repulsors", "Flight") +
                files("/Suits/$m", "specs.pdf" to 3_200_000, "test-flight.mp4" to 640_000_000, "render.png" to 8_800_000))
            put("/Suits/$m/Helmet", files("/Suits/$m/Helmet", "hud-layout.svg" to 120_000, "optics.txt" to 9_000))
        }
        put("/Media", dir("/Media", "Photos", "Videos", "Music") + files("/Media", "expo-keynote.mp4" to 1_200_000_000))
        put("/Media/Photos", files("/Media/Photos", *Array(14) { "IMG_${2041 + it}.jpg" to (2_000_000L + it * 310_000L) }))
        put("/Media/Videos", files("/Media/Videos", "flight-test-01.mp4" to 420_000_000, "flight-test-02.mp4" to 512_000_000))
        put("/Media/Music", files("/Media/Music", "Back in Black.flac" to 31_000_000, "Highway to Hell.flac" to 28_000_000, "Shoot to Thrill.mp3" to 9_000_000))
        put("/Research", dir("/Research", "Element 134", "Quantum") + files("/Research", "notes.txt" to 14_000))
        put("/Documents", files("/Documents", "contract.pdf" to 400_000, "board-minutes.docx" to 120_000, "patents.zip" to 48_000_000))
    }

    override fun root() = FsNode("Drive", "/", true)
    override fun list(node: FsNode): List<FsNode> = tree[node.path].orEmpty()
}
