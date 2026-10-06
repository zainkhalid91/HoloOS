package com.zain.holoos.fs

import java.io.File

private class JvmFileSource(private val home: File) : FileSource {
    override fun root() = FsNode(home.name.ifEmpty { home.path }, home.absolutePath, true)

    override fun list(node: FsNode): List<FsNode> {
        val children = File(node.path).listFiles() ?: return emptyList()
        return children.asSequence()
            .filter { !it.isHidden && !it.name.startsWith(".") && !it.name.startsWith("$") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(160)
            .map { FsNode(it.name, it.absolutePath, it.isDirectory, if (it.isFile) it.length() else 0L) }
            .toList()
    }
}

actual fun platformFileSource(): FileSource =
    if (System.getProperty("holoos.demoFs") == "true") DemoFileSource()
    else JvmFileSource(File(System.getProperty("user.home")))
