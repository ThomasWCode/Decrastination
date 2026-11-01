package com.thomaswcode.decrastination.data

import java.io.File

/**
 * Where a store's text is kept: a file a store on the phone ([FileBackend]), a row on the server. A
 * [JsonStore] holds the state, its version and its migrations; this holds the text, and the copies of it
 * that outlast a change (docs/plan_web/data-and-sync.md §The store backend).
 */
interface StoreBackend {
    /** Store [name]'s text: null where it has none, a failure where it can't be read. */
    fun read(name: String): String?

    /** [text], of the state's [version], as store [name]'s, whole or not at all. */
    fun write(name: String, version: Int, text: String)

    /**
     * [write], and what the same change makes of another text ([remade]; null, nothing): for a backend that may find what this
     * was made from has gone, as the server's does when a command fails.
     */
    fun write(name: String, version: Int, text: String, remade: (String?) -> String?) = write(name, version, text)

    /** The stores it holds, by name. */
    fun names(): List<String>

    /** Whether it holds [name]: a store, or a copy kept of one. */
    fun holds(name: String): Boolean

    /** Store [name] let go. Where that can't be done it's made [empty], so what was cleared can't come back. */
    fun delete(name: String, empty: String)

    /** Store [name]'s text as it is, kept under older shape [from] before a migration writes over it, dated [now]. */
    fun keepShape(name: String, from: Int, now: Long)

    /** A copy set aside already of store [name] that holds what it holds now: it's been kept, and said. Its name, or null. */
    fun alreadyAside(name: String): String?

    /** Store [name]'s text set aside as one that can't be read, dated [now]. The copy's name, or null if it couldn't be made. */
    fun setAside(name: String, now: Long): String?

    /** The copies kept of the stores [of] picks let go, an older shape's and those that couldn't be read, so what's cleared can't come back from one. */
    fun letGoOfCopies(of: (String) -> Boolean)

    /** The older shapes kept of the stores [of] picks let go, those dated [before] or earlier: they've been kept their while. */
    fun letGoOfOldShapes(before: Long, of: (String) -> Boolean)
}

/** The stores as files in [dir], each written to a temporary file that's renamed over the real one. */
class FileBackend(private val dir: File) : StoreBackend {
    private fun fileOf(name: String) = File(dir, name)

    override fun read(name: String): String? = fileOf(name).takeIf { it.exists() }?.readText()

    override fun write(name: String, version: Int, text: String) {
        val file = fileOf(name)
        val parent = requireNotNull(file.absoluteFile.parentFile) { "A store needs a parent directory" }
        parent.mkdirs()
        val tmp = File(parent, "$name.tmp")
        tmp.writeText(text)
        JsonStore.moveOver(tmp, file)
    }

    override fun names(): List<String> = dir.list().orEmpty().filter { it.endsWith(".json") }

    override fun holds(name: String): Boolean = fileOf(name).isFile

    override fun delete(name: String, empty: String) {
        val file = fileOf(name)
        if (!file.delete() && file.exists()) runCatching { file.writeText(empty) }
    }

    override fun keepShape(name: String, from: Int, now: Long) {
        val file = fileOf(name)
        val old = File(file.absoluteFile.parentFile, "$name.v$from")
        if (!old.exists()) {
            file.copyTo(old)
            old.setLastModified(now)
        }
    }

    override fun alreadyAside(name: String): String? = JsonStore.alreadyAside(fileOf(name))?.name

    override fun setAside(name: String, now: Long): String? = JsonStore.setAside(fileOf(name), now)?.name

    override fun letGoOfCopies(of: (String) -> Boolean) {
        dir.absoluteFile.listFiles { f -> f.isFile && copyOf(f.name)?.let(of) == true }.orEmpty().forEach { f ->
            if (!f.delete() && f.exists()) runCatching { f.writeText("") }
        }
    }

    override fun letGoOfOldShapes(before: Long, of: (String) -> Boolean) {
        dir.absoluteFile.listFiles { f -> f.isFile && shapeOf(f.name)?.let(of) == true && f.lastModified() <= before }.orEmpty().forEach { it.delete() }
    }

    /** The store [file] is an older shape of (`<name>.v<n>`), or null. */
    private fun shapeOf(file: String): String? = file.takeIf { it.substringAfterLast(".v", "").toIntOrNull() != null }?.substringBeforeLast(".v")

    /** The store [file] is a copy of: an older shape's, or one that couldn't be read (`<name>.unreadable-<when>`); null for neither. */
    private fun copyOf(file: String): String? = shapeOf(file) ?: file.substringBefore(".unreadable-").takeIf { it != file }
}
