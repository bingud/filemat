package org.filemat.server.module.file.service

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.util.localDrivePath
import org.filemat.server.common.util.reachesNetwork
import org.filemat.server.common.util.resolvePath
import org.filemat.server.common.util.unixNow
import org.filemat.server.common.util.withRealNames
import org.filemat.server.config.Props
import org.filemat.server.config.platform.FileIdentity
import org.filemat.server.config.platform.PathPolicy
import org.filemat.server.config.platform.Platform
import org.filemat.server.config.properties.SensitiveFolderPaths
import org.filemat.server.module.file.model.FilePath
import org.filemat.server.module.file.model.IFileVisibility
import org.filemat.server.module.file.model.VisibilityTrie
import org.filemat.server.module.file.repository.FileVisibilityRepository
import org.filemat.server.module.log.model.LogType
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.user.model.UserAction
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Path
import kotlin.system.exitProcess

@Service
class FileVisibilityService(
    private val fileVisibilityRepository: FileVisibilityRepository,
    private val logService: LogService,
    private val state: State,
    private val sensitiveFolderPaths: SensitiveFolderPaths,
) {
    private val visibilityTrie = VisibilityTrie()
    private val hiddenFileTrie = VisibilityTrie()
    private val windowsHiddenRootNames = listOf("\$Recycle.Bin", "System Volume Information", "pagefile.sys", "hiberfil.sys", "swapfile.sys")

    fun getAllFileVisibilities() = visibilityTrie.getAllVisibilities()

    /**
     * Initialize folder visibility service
     */
    fun initialize() {
        println("Loading file visibility configurations...\n")

        loadHiddenFiles()

        val visibilityData = runCatching { fileVisibilityRepository.getAll() }
            .onFailure {
                it.printStackTrace()
                println("FAILED TO LOAD FILE VISIBILITY DATA FROM DATABASE.")
                exitProcess(1)
            }.getOrNull() ?: emptyList()

        // Keys are computed now, so a folder made case-sensitive while the server was stopped matches again
        visibilityData.forEach { visibilityTrie.insert(PathPolicy.pathKey(it.path), it.isExposed, it.path) }

        if (visibilityData.isNotEmpty()) {
            val exposedPaths = visibilityData.filter { it.isExposed }.map { it.path }
            val hiddenPaths = visibilityData.filterNot { it.isExposed }.map { it.path }
            println("File visibility configurations loaded:\n### EXPOSED FILES:\n${exposedPaths.joinToString("\t")}\n\n### HIDDEN FILES:\n${hiddenPaths.joinToString("\t")}")
        } else {
            println("No files have been exposed or hidden.")
        }

        visibilityData.filter { it.isExposed }.forEach { warnIfUnstableMount(it.path) }
        // Rules saved before F14 could go through a link or a short name
        visibilityData.forEach { rule -> rulePathProblem(rule.path)?.let { println("\nWARNING: the rule for ${rule.path} never matches. $it") } }
    }

    /**
     * Hides the authentication code file and the folders from `FM_HIDDEN_FOLDER_PATHS`.
     * Needs no database, so it also runs before setup: folders exposed during setup are covered right away.
     */
    fun loadHiddenFiles() {
        hiddenFileTrie.insert(PathPolicy.pathKey(Props.authCodeFile), true)
        // Insert true because VisibilityTrie returns false by default
        state.app.hiddenFolders.forEach { hidden ->
            hiddenFileTrie.insert(PathPolicy.pathKey(hidden), true)
            // Requests are checked at their real path, so a folder typed through a link or a short name is hidden there too (F14)
            val real = realRulePath(hidden) ?: return@forEach
            if (real.pathKey != PathPolicy.pathKey(hidden)) {
                hiddenFileTrie.insert(real.pathKey, true)
                println("Hidden folder $hidden is also hidden at its real path ${real.pathString}.")
            }
        }
    }

    /**
     * Whether a path is one of the system files at the root of a drive, or inside one (Q62).
     * They are on the sensitive list too, but stay hidden when it is off. Checked by name, so drives plugged in later are covered.
     */
    private fun isWindowsSystemFile(path: Path): Boolean {
        if (!Platform.isWindows || path.root == null || path.nameCount == 0) return false
        val name = path.getName(0).toString()
        return windowsHiddenRootNames.any { it.equals(name, ignoreCase = true) }
    }

    private fun warnIfUnstableMount(path: String) {
        val type = runCatching { FileIdentity.unstableMountType(Path.of(path)) }.getOrNull() ?: return
        println("\nWARNING: the exposed folder $path is on a $type mount (Docker Desktop or WSL). Inode numbers there may change after a restart, and then permissions and shares lose their files. Use a Docker volume or a folder on the Linux filesystem instead.")
    }

    /**
     * returns if a folder path is not blocked
     */
    fun isPathAllowed(canonicalPath: FilePath): String? {
        if (state.app.hideSensitiveFolders && sensitiveFolderPaths.contains(canonicalPath.pathString, isPathNormalized = true)) {
            return "This file is blocked because it is marked as sensitive."
        }

        if (!state.app.allowReadDataFolder && state.app.isInDataFolder(canonicalPath.path)) return "${Props.appName} data folder is blocked."

        val isForceHidden = hiddenFileTrie.getVisibility(canonicalPath.pathKey)
        // isExposed is set to true because all folders in this list are forcefully hidden
        if (isForceHidden.isExposed == true || isWindowsSystemFile(canonicalPath.path)) return "This file is blocked."

        val visibility = visibilityTrie.getVisibility(canonicalPath.pathKey)
        return if (visibility.isExposed) null else "This file is not exposed."
    }

    /**
     * Windows: why [path] can't be exposed or hidden, when it is a network folder (`\\server\share`), on a network drive (`Z:`)
     * or a link to one. Such paths are never served, so the setting would do nothing. Null for every other path.
     */
    fun networkPathProblem(path: String): String? {
        if (!Platform.isWindows) return null
        val problem = "Network drives and network folders (\\\\server\\share) aren't supported: $path"
        if (path.replace('\\', '/').startsWith("//")) return problem
        val parsed = runCatching { PathPolicy.parse(path) }.getOrNull() ?: return null
        if (!parsed.isAbsolute) return null
        val isNetwork = localDrivePath(FilePath.ofAlreadyNormalized(parsed)) == null || reachesNetwork(parsed)
        return if (isNetwork) problem else null
    }

    /**
     * The path a rule for [path] has to have to match: requests are checked at their real path, with links followed and real names.
     * Only the folders above it are resolved: a rule on a link itself still hides the link in its folder.
     * A folder that doesn't exist yet stays as typed. Null when [path] can't be resolved (not absolute, the network).
     */
    fun realRulePath(path: String): FilePath? = runCatching {
        val typed = FilePath.of(path)
        if (PathPolicy.isVirtualRoot(typed.path) || !typed.path.isAbsolute) return null
        val parent = typed.path.parent ?: return withRealNames(typed)
        val realParent = resolvePath(FilePath.ofAlreadyNormalized(parent), followSymlinks = true, allowNonExistent = true).valueOrNull ?: return null
        withRealNames(FilePath.ofAlreadyNormalized(PathPolicy.normalize(realParent.path.resolve(typed.path.fileName.toString()))))
    }.getOrNull()

    /**
     * Why a rule for [path] would never match anything (F14): a folder in it is a link, or a name in it is an 8.3 short name (`JOHNSM~1`).
     * Null when the path is already the real one.
     */
    fun rulePathProblem(path: String): String? {
        val real = realRulePath(path) ?: return null
        if (real.pathKey == PathPolicy.pathKey(path)) return null
        return "This path goes through a link or a short name, so the rule would never match. Use the real path: ${real.pathString}"
    }

    /**
     * Create new folder visibility configurations
     */
    fun insertPaths(paths: List<IFileVisibility>, userAction: UserAction): Result<Unit> {
        paths.firstNotNullOfOrNull { networkPathProblem(it.path) ?: rulePathProblem(it.path) }?.let { return Result.reject(it) }

        try {
            // Check if any path already exists
            paths.forEach {
                val hasRule = visibilityTrie.hasExplicitRule(PathPolicy.pathKey(it.path))
                if (hasRule) return Result.reject("Added path already exists.")
            }

            val now = unixNow()
            paths.forEach { fileVisibilityRepository.insertOrReplace(it.path, it.isExposed, now) }

            // Inside a transaction (setup), memory changes only once the rows are committed.
            // Otherwise a rolled-back setup would leave the folders exposed in memory, and every retry would find them "already" there.
            val applyToMemory = { paths.forEach { visibilityTrie.insert(PathPolicy.pathKey(it.path), it.isExposed, it.path) } }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = applyToMemory()
                })
            } else {
                applyToMemory()
            }
            return Result.ok(Unit)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to insert file visibility to database",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to save file visibility configuration.")
        }
    }

    fun removePath(path: String, userAction: UserAction): Result<Unit> {
        try {
            fileVisibilityRepository.remove(path)
        } catch (e: Exception) {
            logService.error(
                type = LogType.SYSTEM,
                action = userAction,
                description = "Failed to remove file visibility from database",
                message = e.stackTraceToString()
            )
            return Result.error("Failed to remove file visibility configuration.")
        }

        visibilityTrie.remove(PathPolicy.pathKey(path))
        return Result.ok()
    }

    /**
     * Create new folder visibility configurations
     */
    fun insertPath(path: IFileVisibility, userAction: UserAction): Result<Unit> = insertPaths(listOf(path), userAction)
}