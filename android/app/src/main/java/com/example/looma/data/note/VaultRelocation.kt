package com.example.looma.data.note

import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.WikiLinks
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.Vault
import com.example.looma.domain.wiki.WikiGraph
import com.example.looma.domain.wiki.WikiNote
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

internal data class RelocationChanges(
    val removedNotes: List<NotePath> = emptyList(),
    val changedNotes: List<NotePath> = emptyList(),
) {
    operator fun plus(other: RelocationChanges): RelocationChanges = RelocationChanges(
        removedNotes = removedNotes + other.removedNotes,
        changedNotes = changedNotes + other.changedNotes,
    )
}

internal data class RecoveryReport(
    val changes: RelocationChanges,
    val stalled: StalledRelocation?,
)

internal data class RelocationResult<P>(
    val path: P,
    val linksUpdated: Int,
    val linksFailed: Int,
    val changes: RelocationChanges,
)

internal class VaultRelocation(private val vault: Vault) {
    private val ops = VaultFileOps(File(vault.rootPath))
    private val root = ops.rootDir

    internal var afterIo: (String) -> Unit = {}

    fun recoverPending(): RecoveryReport {
        val directory = safeFile(OPERATIONS)
        if (!exists(directory)) return RecoveryReport(RelocationChanges(), null)
        val entries = children(directory).filter { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
        entries.filter(::isUnpublished).forEach(::sweep)
        val journals = entries
            .filter { JOURNAL_NAME.matches(it.name) && exists(File(it, "intent.properties")) }
            .sortedBy { it.name }
        var changes = RelocationChanges()
        for (journal in journals) {
            try {
                changes += recoverJournal(journal)
            } catch (_: IOException) {
                return RecoveryReport(changes, stalledOf(journal))
            } catch (_: IllegalArgumentException) {
                return RecoveryReport(changes, stalledOf(journal))
            }
        }
        return RecoveryReport(changes, null)
    }

    fun abandon(journalId: String) {
        if (!JOURNAL_NAME.matches(journalId)) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        val journal = safeFile("$OPERATIONS/$journalId")
        if (!exists(journal)) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val abandoned = safeFile(ABANDONED_OPERATIONS)
        createDirectories(abandoned)
        val target = File(abandoned, journalId)
        if (exists(target)) conflict(target)
        Files.move(journal.toPath(), target.toPath(), ATOMIC_MOVE)
        journal.parentFile?.let(::syncDirectory)
        syncDirectory(abandoned)
    }

    fun renameNote(id: NoteId, name: String): RelocationResult<NotePath> {
        val recovered = recoverOrThrow()
        val source = noteFile(id)
        return relocate(source, ops.renameDestination(source, name), recovered, ::NotePath)
    }

    fun moveNote(id: NoteId, target: FolderPath): RelocationResult<NotePath> {
        val recovered = recoverOrThrow()
        val source = noteFile(id)
        safeFile(target.relativePath, allowRoot = true)
        return relocate(source, ops.moveDestination(source, target), recovered, ::NotePath)
    }

    fun renameFolder(path: FolderPath, name: String): RelocationResult<FolderPath> {
        val recovered = recoverOrThrow()
        val source = safeFile(path.relativePath)
        return relocate(source, ops.renameFolderDestination(path, name), recovered, ::FolderPath)
    }

    fun moveFolder(path: FolderPath, target: FolderPath): RelocationResult<FolderPath> {
        val recovered = recoverOrThrow()
        val source = safeFile(path.relativePath)
        safeFile(target.relativePath, allowRoot = true)
        return relocate(source, ops.moveFolderDestination(path, target), recovered, ::FolderPath)
    }

    private fun recoverOrThrow(): RelocationChanges {
        val report = recoverPending()
        report.stalled?.let { throw RelocationStalledException(it) }
        return report.changes
    }

    private fun isUnpublished(directory: File): Boolean =
        STAGING_NAME.matches(directory.name) ||
            (JOURNAL_NAME.matches(directory.name) && !exists(File(directory, "intent.properties")))

    private fun sweep(directory: File) {
        val files = directory.listFiles() ?: return
        files.filter { SWEEPABLE_FILE.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
            .forEach { it.delete() }
        if (directory.listFiles()?.isEmpty() == true) directory.delete()
    }

    private fun recoverJournal(journal: File): RelocationChanges {
        safeFile(relative(journal))
        val manifest = readBytes(File(journal, "intent.properties"), MAX_MANIFEST_BYTES)
        val plan = decode(manifest)
        val completed = File(journal, "completed")
        if (exists(completed)) {
            if (!readBytes(completed, 64).contentEquals(hash(manifest).toByteArray())) conflict(completed)
            cleanJournal(journal, plan, manifest)
        } else {
            replay(journal, plan, manifest)
        }
        return changesOf(plan)
    }

    private fun stalledOf(journal: File): StalledRelocation {
        val namespace = try {
            decode(readBytes(File(journal, "intent.properties"), MAX_MANIFEST_BYTES)).moves.first()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return StalledRelocation(
            journalId = journal.name,
            sourcePath = namespace?.source.orEmpty(),
            destinationPath = namespace?.destination.orEmpty(),
        )
    }

    private fun changesOf(plan: Plan): RelocationChanges {
        val namespace = plan.moves.first()
        val movedNotes = namespace.members.filter { member ->
            member.hash != null &&
                member.path.split('/').none { it.startsWith('.') } &&
                join(namespace.source, member.path).endsWith(".md", ignoreCase = true)
        }
        val rewrittenNotes = plan.writes.map { it.path }.filterNot { it.startsWith(".looma/") }
        return RelocationChanges(
            removedNotes = movedNotes.map { NotePath(join(namespace.source, it.path)) },
            changedNotes = (movedNotes.map { join(namespace.destination, it.path) } + rewrittenNotes)
                .distinct()
                .map(::NotePath),
        )
    }

    private fun noteFile(id: NoteId): File {
        if (id.vaultId != vault.id) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        validateNotePath(id.path.relativePath)
        safeFile(id.path.relativePath)
        return ops.noteFile(id.path)
    }

    private fun <P> relocate(
        source: File,
        destination: File,
        recovered: RelocationChanges,
        pathOf: (String) -> P,
    ): RelocationResult<P> {
        val from = relative(source)
        val to = relative(destination)
        safeFile(from)
        safeFile(to)
        if (from == to) return RelocationResult(pathOf(to), linksUpdated = 0, linksFailed = 0, changes = recovered)
        val notePaths = listNotePaths()
        val paths = notePaths.associateWith { path ->
            when {
                path == from -> to
                source.isDirectory && path.startsWith("$from/") -> to + path.removePrefix(from)
                else -> path
            }
        }
        val before = graph(notePaths)
        val after = graph(paths.values.toList())
        val rewrites = mutableListOf<Rewrite>()
        val guards = mutableListOf<Guard>()
        var linksUpdated = 0
        var linksFailed = 0
        for (path in notePaths) {
            val bytes = readReferrer(path) ?: continue
            val relinked = relink(text(bytes), path, paths.getValue(path), paths, before, after)
            linksFailed += relinked.failed
            val content = relinked.content ?: continue
            rewrites += Rewrite(paths.getValue(path), bytes, markdownBytes(content))
            guards += Guard(path, hash(bytes))
            linksUpdated += relinked.updated
        }
        val moves = mutableListOf(Move(from, to, inventory(source)))
        for (path in notePaths) {
            val newPath = paths.getValue(path)
            for (suffix in listOf(".properties", ".md")) {
                val oldDraft = "$DRAFTS/${draftKey(path)}$suffix"
                val newDraft = "$DRAFTS/${draftKey(newPath)}$suffix"
                val file = safeFile(oldDraft)
                if (oldDraft != newDraft && exists(safeFile(newDraft))) conflict(safeFile(newDraft))
                if (!exists(file)) continue
                val bytes = readBytes(file, MAX_DRAFT_BYTES)
                guards += Guard(oldDraft, hash(bytes))
                if (oldDraft != newDraft) moves += Move(oldDraft, newDraft, listOf(Member("", hash(bytes))))
                val properties = if (suffix == ".properties") properties(bytes) else null
                val body = properties?.getProperty("content") ?: if (properties == null) text(bytes) else throw IOException("Invalid draft: $oldDraft")
                val updated = relink(body, path, newPath, paths, before, after).content ?: continue
                val replacement = if (properties == null) {
                    updated.toByteArray(Charsets.UTF_8)
                } else {
                    properties.setProperty("content", updated)
                    encodeProperties(properties)
                }
                rewrites += Rewrite(newDraft, bytes, replacement)
            }
        }
        val directories = missingParents(destination)
        val plan = Plan(moves, rewrites.map { it.record() }, directories, linksUpdated, linksFailed)
        validate(plan)
        if (listNotePaths() != notePaths) throw IOException("Vault changed while planning relocation")
        guards.forEach { if (fileHash(safeFile(it.path)) != it.hash) conflict(safeFile(it.path)) }
        checkState(plan)
        val journal = publish(plan, rewrites)
        try {
            journal.parentFile?.let(::syncDirectory)
            afterIo("intent")
            replay(journal, plan, readBytes(File(journal, "intent.properties"), MAX_MANIFEST_BYTES))
        } catch (error: IOException) {
            throw RelocationStalledException(stalledOf(journal), error)
        }
        return RelocationResult(pathOf(to), linksUpdated, linksFailed, recovered + changesOf(plan))
    }

    private fun graph(paths: List<String>) = WikiGraph(
        paths.map { WikiNote(NoteId(vault.id, NotePath(it)), it) },
        emptyMap(),
    )

    private fun relink(
        content: String,
        oldPath: String,
        newPath: String,
        paths: Map<String, String>,
        before: WikiGraph,
        after: WikiGraph,
    ): Relinked {
        val bom = if (content.startsWith('\uFEFF')) "\uFEFF" else ""
        val body = content.removePrefix(bom)
        val spans = MarkdownIndex.index(body, oldPath).links
        var updated = 0
        var failed = 0
        val rewritten = WikiLinks.retarget(body, spans) { target ->
            val original = before.resolve(target, NotePath(oldPath)) ?: return@retarget null
            val intended = NoteId(vault.id, NotePath(paths.getValue(original.path.relativePath)))
            if (after.resolve(target, NotePath(newPath)) == intended) return@retarget null
            val full = intended.path.relativePath.dropLast(3)
            if (WikiLinks.occurrences("[[$full]]").singleOrNull()?.target != full || after.resolve(full, NotePath(newPath)) != intended) {
                failed++
                return@retarget null
            }
            updated++
            full
        }
        return Relinked(rewritten?.let { bom + it }, updated, failed)
    }

    private fun listNotePaths(): List<String> {
        val notes = mutableListOf<String>()
        fun visit(directory: File, depth: Int) {
            val entries = directory.listFiles() ?: return
            for (child in entries.sortedBy { it.name }) {
                val path = child.toPath()
                if (Files.isSymbolicLink(path)) continue
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    if (!child.name.startsWith('.') && depth + 1 < MAX_DEPTH) visit(child, depth + 1)
                } else if (Files.isRegularFile(path, NOFOLLOW_LINKS) && child.extension.equals("md", ignoreCase = true)) {
                    notes += relative(child)
                }
            }
        }
        visit(root, 0)
        return notes
    }

    private fun readReferrer(path: String): ByteArray? = try {
        val file = safeFile(path)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > MAX_MARKDOWN_BYTES) {
            null
        } else {
            readBytesBounded(file, MAX_MARKDOWN_BYTES).also { text(it) }
        }
    } catch (_: IOException) {
        null
    }

    private fun inventory(source: File): List<Member> {
        val result = mutableListOf<Member>()
        val budget = Budget()
        fun visit(file: File, path: String, depth: Int) {
            budget.node(depth)
            safeFile(relative(file))
            if (file.isDirectory) {
                result += Member(path, null)
                children(file).sortedBy { it.name }.forEach { child ->
                    visit(child, if (path.isEmpty()) child.name else "$path/${child.name}", depth + 1)
                }
            } else {
                result += Member(path, fileHash(file))
            }
        }
        visit(source, "", 0)
        return result
    }

    private fun checkState(plan: Plan): List<Boolean> {
        val moved = plan.moves.map { move ->
            val source = safeFile(move.source)
            val destination = safeFile(move.destination)
            val applied = when {
                exists(source) && !exists(destination) -> false
                !exists(source) && exists(destination) -> true
                else -> conflict(source)
            }
            val current = inventory(if (applied) destination else source)
            if (current.map { it.path } != move.members.map { it.path }) conflict(if (applied) destination else source)
            current.zip(move.members).forEach { (actual, expected) ->
                val path = join(move.destination, expected.path)
                val replacement = plan.writes.firstOrNull { it.path == path }
                if (actual.hash != expected.hash && !(applied && replacement != null && actual.hash == replacement.afterHash)) {
                    conflict(safeFile(path))
                }
            }
            applied
        }
        for (write in plan.writes) {
            val index = plan.moves.indexOfFirst { write.path == it.destination || write.path.startsWith("${it.destination}/") }
            val currentPath = if (index >= 0 && !moved[index]) {
                plan.moves[index].source + write.path.removePrefix(plan.moves[index].destination)
            } else write.path
            val file = safeFile(currentPath)
            val hash = fileHash(file)
            if (hash != write.beforeHash && hash != write.afterHash) conflict(file)
        }
        return moved
    }

    private fun replay(journal: File, plan: Plan, manifest: ByteArray) {
        val staged = plan.writes.mapIndexed { index, write ->
            val before = readBytes(File(journal, "$index.before"), MAX_DRAFT_BYTES)
            val after = readBytes(File(journal, "$index.after"), MAX_DRAFT_BYTES)
            if (hash(before) != write.beforeHash || hash(after) != write.afterHash) conflict(journal)
            after
        }
        checkState(plan)
        var progress = 0
        for (path in plan.directories) {
            val directory = safeFile(path)
            if (!exists(directory)) {
                if (!directory.mkdir()) throw IOException("Cannot create relocation directory: $path")
                syncDirectory(directory.parentFile)
                afterIo("mkdir")
            } else if (!directory.isDirectory) conflict(directory)
        }
        for (move in plan.moves) {
            val source = safeFile(move.source)
            val destination = safeFile(move.destination)
            if (exists(source)) {
                checkState(plan)
                if (exists(destination)) conflict(destination)
                Files.move(source.toPath(), destination.toPath())
                source.parentFile?.let(::syncDirectory)
                if (source.parentFile != destination.parentFile) destination.parentFile?.let(::syncDirectory)
                afterIo("move")
            }
            writeDurably(File(journal, "progress"), (++progress).toString().toByteArray())
            afterIo("progress")
        }
        plan.writes.forEachIndexed { index, write ->
            val file = safeFile(write.path)
            when (fileHash(file)) {
                write.afterHash -> Unit
                write.beforeHash -> {
                    writeBytesAtomically(file, staged[index]) {
                        safeFile(write.path)
                        if (fileHash(file) != write.beforeHash) conflict(file)
                    }
                    file.parentFile?.let(::syncDirectory)
                    afterIo("replace")
                }
                else -> conflict(file)
            }
            writeDurably(File(journal, "progress"), (++progress).toString().toByteArray())
            afterIo("progress")
        }
        checkState(plan)
        writeDurably(File(journal, "completed"), hash(manifest).toByteArray())
        afterIo("complete")
        cleanJournal(journal, plan, manifest)
    }

    private fun publish(plan: Plan, rewrites: List<Rewrite>): File {
        val bytes = encode(plan)
        if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("Relocation journal is too large")
        val directory = safeFile(OPERATIONS)
        createDirectories(directory)
        val id = UUID.randomUUID()
        val staging = File(directory, "staging-$id")
        if (!staging.mkdir()) throw IOException("Cannot create relocation journal")
        syncDirectory(directory)
        rewrites.forEachIndexed { index, write ->
            writeDurably(File(staging, "$index.before"), write.before)
            writeDurably(File(staging, "$index.after"), write.after)
        }
        writeDurably(File(staging, "intent.properties"), bytes)
        val journal = File(directory, "op-$id")
        Files.move(staging.toPath(), journal.toPath(), ATOMIC_MOVE)
        return journal
    }

    private fun cleanJournal(journal: File, plan: Plan, manifest: ByteArray) {
        fun deleteKnown(name: String, expected: String) {
            val file = File(journal, name)
            if (exists(file) && fileHash(file) == expected && !file.delete()) throw IOException("Cannot clean relocation journal")
        }
        plan.writes.forEachIndexed { index, write ->
            deleteKnown("$index.before", write.beforeHash)
            deleteKnown("$index.after", write.afterHash)
        }
        deleteKnown("progress", hash((plan.moves.size + plan.writes.size).toString().toByteArray()))
        deleteKnown("intent.properties", hash(manifest))
        syncDirectory(journal)
        deleteKnown("completed", hash(hash(manifest).toByteArray()))
        if (children(journal).isEmpty() && !journal.delete()) throw IOException("Cannot clean relocation journal")
        journal.parentFile?.let(::syncDirectory)
    }

    private fun encode(plan: Plan): ByteArray = encodeProperties(Properties().apply {
        setProperty("version", "1")
        setProperty("linksUpdated", plan.linksUpdated.toString())
        setProperty("linksFailed", plan.linksFailed.toString())
        setProperty("moves", plan.moves.size.toString())
        plan.moves.forEachIndexed { index, move ->
            val prefix = "move.$index"
            setProperty("$prefix.source", move.source)
            setProperty("$prefix.destination", move.destination)
            setProperty("$prefix.members", move.members.size.toString())
            move.members.forEachIndexed { memberIndex, member ->
                setProperty("$prefix.$memberIndex.path", member.path)
                setProperty("$prefix.$memberIndex.hash", member.hash ?: "directory")
            }
        }
        setProperty("writes", plan.writes.size.toString())
        plan.writes.forEachIndexed { index, write ->
            setProperty("write.$index.path", write.path)
            setProperty("write.$index.before", write.beforeHash)
            setProperty("write.$index.after", write.afterHash)
        }
        setProperty("directories", plan.directories.size.toString())
        plan.directories.forEachIndexed { index, path -> setProperty("directory.$index", path) }
    })

    private fun decode(bytes: ByteArray): Plan {
        val data = properties(bytes)
        fun value(key: String) = data.getProperty(key) ?: throw IOException("Missing journal field: $key")
        fun count(key: String): Int = value(key).toIntOrNull()?.takeIf { it in 0..MAX_ENTRIES }
            ?: throw IOException("Invalid journal count: $key")
        fun linkCount(key: String, fallback: String?): Int = (data.getProperty(key) ?: fallback)
            ?.toIntOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid link count")
        if (value("version") != "1") throw IOException("Unknown relocation journal version")
        return Plan(
            moves = List(count("moves")) { index ->
                val prefix = "move.$index"
                Move(value("$prefix.source"), value("$prefix.destination"), List(count("$prefix.members")) { member ->
                    Member(value("$prefix.$member.path"), value("$prefix.$member.hash").takeUnless { it == "directory" })
                })
            },
            writes = List(count("writes")) { WriteRecord(value("write.$it.path"), value("write.$it.before"), value("write.$it.after")) },
            directories = List(count("directories")) { value("directory.$it") },
            linksUpdated = linkCount("linksUpdated", fallback = null),
            linksFailed = linkCount("linksFailed", fallback = "0"),
        ).also(::validate)
    }

    private fun validate(plan: Plan) {
        if (plan.moves.isEmpty() || plan.moves.size > MAX_ENTRIES || plan.writes.size > MAX_ENTRIES ||
            plan.moves.sumOf { it.members.size.toLong() } > MAX_ENTRIES || plan.directories.size > MAX_DEPTH
        ) throw IOException("Invalid relocation journal size")
        val namespace = plan.moves.first()
        for ((index, move) in plan.moves.withIndex()) {
            safeFile(move.source)
            safeFile(move.destination)
            if (move.source == move.destination || move.destination.startsWith("${move.source}/") || move.source.startsWith("${move.destination}/")) {
                throw IOException("Invalid relocation move")
            }
            if (index == 0) {
                if (move.members.firstOrNull()?.hash == null) {
                    validateVisiblePath(move.source)
                    validateVisiblePath(move.destination)
                } else {
                    validateNotePath(move.source)
                    validateNotePath(move.destination)
                }
            } else if (!DRAFT_PATH.matches(move.source) || !DRAFT_PATH.matches(move.destination)) {
                throw IOException("Invalid draft relocation")
            }
            if (move.members.isEmpty() || move.members.first().path != "" || move.members.map { it.path }.distinct().size != move.members.size) {
                throw IOException("Invalid relocation inventory")
            }
            move.members.forEach { member ->
                safeFile(join(move.source, member.path))
                safeFile(join(move.destination, member.path))
                if (member.hash != null && !HASH.matches(member.hash)) throw IOException("Invalid inventory hash")
            }
            if (index > 0 && (move.members.size != 1 || move.members.single().hash == null)) throw IOException("Invalid draft inventory")
        }
        if ((plan.moves.map { it.source } + plan.moves.map { it.destination }).distinct().size != plan.moves.size * 2) {
            throw IOException("Overlapping relocation moves")
        }
        if (plan.writes.map { it.path }.distinct().size != plan.writes.size) throw IOException("Duplicate relocation writes")
        plan.writes.forEach { write ->
            safeFile(write.path)
            if (!DRAFT_PATH.matches(write.path)) validateNotePath(write.path)
            if (!HASH.matches(write.beforeHash) || !HASH.matches(write.afterHash)) throw IOException("Invalid write hash")
        }
        plan.directories.forEach { path ->
            validateVisiblePath(path)
            safeFile(path)
            if (!namespace.destination.startsWith("$path/")) throw IOException("Invalid relocation directory")
        }
    }

    private fun validateNotePath(path: String) {
        if (path.split('/').dropLast(1).any { it.startsWith('.') } || !path.endsWith(".md", ignoreCase = true)) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
    }

    private fun validateVisiblePath(path: String) {
        if (path.split('/').any { it.startsWith('.') }) throw VaultFileException(VaultFileFailure.INVALID_PATH)
    }

    private fun safeFile(path: String, allowRoot: Boolean = false): File {
        if (path.isEmpty() && allowRoot) return root
        val components = path.split('/')
        if (components.size > MAX_DEPTH || components.any { it.isBlank() || it == "." || it == ".." } || path.any { it == '\\' || it == ':' || it.isISOControl() }) {
            throw VaultFileException(VaultFileFailure.INVALID_PATH)
        }
        var file = root
        for (component in components) {
            file = File(file, component)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile) {
                throw VaultFileException(VaultFileFailure.INVALID_PATH)
            }
        }
        if (!file.toPath().startsWith(root.toPath()) || file == root) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        return file
    }

    private fun missingParents(file: File): List<String> {
        val missing = mutableListOf<String>()
        var parent: File? = file.parentFile
        while (parent != null && parent != root && !exists(parent)) {
            missing += relative(parent)
            parent = parent.parentFile
        }
        if (parent == null || !parent.isDirectory) conflict(parent ?: root)
        return missing.asReversed()
    }

    private fun createDirectories(directory: File) {
        val paths = missingParents(File(directory, "placeholder"))
        for (path in paths) {
            val file = safeFile(path)
            if (!file.mkdir()) throw IOException("Cannot create journal directory")
            file.parentFile?.let(::syncDirectory)
        }
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        safeFile(relative(file))
        writeBytesAtomically(file, bytes)
        file.parentFile?.let(::syncDirectory)
    }

    private fun syncDirectory(directory: File) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    }

    private fun children(directory: File): Array<File> = directory.listFiles()
        ?: throw IOException("Cannot enumerate vault directory: ${directory.name}")

    private fun readBytes(file: File, limit: Long): ByteArray {
        safeFile(relative(file))
        if (!file.isFile || file.length() > limit) throw IOException("Unreadable or oversized relocation file: ${file.name}")
        return readBytesBounded(file, limit)
    }

    private fun fileHash(file: File): String {
        safeFile(relative(file))
        if (!file.isFile) conflict(file)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun draftKey(path: String): String = hash(path.toByteArray(Charsets.UTF_8)).take(32)
    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
    private fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun join(parent: String, child: String): String = if (child.isEmpty()) parent else "$parent/$child"
    private fun conflict(file: File): Nothing = throw IOException("Unfamiliar relocation state: ${relative(file)}")
    private fun properties(bytes: ByteArray): Properties = try {
        Properties().apply { bytes.inputStream().use { load(it) } }
    } catch (error: IllegalArgumentException) {
        throw IOException("Invalid relocation properties", error)
    }
    private fun encodeProperties(data: Properties): ByteArray = ByteArrayOutputStream().use { output -> data.store(output, null); output.toByteArray() }

    private data class Relinked(val content: String?, val updated: Int, val failed: Int)
    private data class Guard(val path: String, val hash: String)
    private data class Member(val path: String, val hash: String?)
    private data class Move(val source: String, val destination: String, val members: List<Member>)
    private data class WriteRecord(val path: String, val beforeHash: String, val afterHash: String)
    private inner class Rewrite(val path: String, val before: ByteArray, val after: ByteArray) {
        fun record() = WriteRecord(path, hash(before), hash(after))
    }
    private data class Plan(
        val moves: List<Move>,
        val writes: List<WriteRecord>,
        val directories: List<String>,
        val linksUpdated: Int,
        val linksFailed: Int,
    )
    private class Budget {
        private var entries = 0
        fun node(depth: Int) {
            if (++entries > MAX_ENTRIES || depth > MAX_DEPTH) throw IOException("Vault traversal limit exceeded")
        }
    }

    private companion object {
        const val OPERATIONS = ".looma/operations"
        const val ABANDONED_OPERATIONS = ".looma/abandoned-operations"
        const val DRAFTS = ".looma/drafts"
        const val MAX_ENTRIES = 20000
        const val MAX_DEPTH = 64
        const val MAX_DRAFT_BYTES = MAX_MARKDOWN_BYTES * 6 + 4096
        const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        val HASH = Regex("[0-9a-f]{64}")
        val DRAFT_PATH = Regex("\\.looma/drafts/[0-9a-f]{32}\\.(properties|md)")
        val JOURNAL_NAME = Regex("op-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val STAGING_NAME = Regex("staging-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val SWEEPABLE_FILE = Regex("[0-9]+\\.(before|after)|intent\\.properties|progress|completed|\\.looma-[^/]*\\.tmp")
    }
}
