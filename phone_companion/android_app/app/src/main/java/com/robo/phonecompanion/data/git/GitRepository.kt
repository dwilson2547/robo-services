package com.robo.phonecompanion.data.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * Wraps JGit for the operations needed to sync DBCs, sidecars, and vehicle
 * profiles to a remote HTTPS git repository authenticated via PAT.
 *
 * All methods are blocking — call from a background thread or coroutine.
 */
class GitRepository(private val workDir: File) {

    data class SyncStatus(
        val added: List<String>,
        val modified: List<String>,
        val deleted: List<String>,
    ) {
        val hasChanges: Boolean get() = added.isNotEmpty() || modified.isNotEmpty() || deleted.isNotEmpty()
    }

    private fun creds(token: String) =
        UsernamePasswordCredentialsProvider("token", token)

    val isInitialized: Boolean get() = File(workDir, ".git").exists()

    fun clone(repoUrl: String, token: String) {
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()
        Git.cloneRepository()
            .setURI(repoUrl)
            .setDirectory(workDir)
            .setCredentialsProvider(creds(token))
            .call()
            .close()
    }

    fun pull(token: String) {
        openGit().use { git ->
            git.pull()
                .setCredentialsProvider(creds(token))
                .call()
        }
    }

    fun status(): SyncStatus {
        openGit().use { git ->
            val s = git.status().call()
            return SyncStatus(
                added = (s.untracked + s.added).sorted(),
                modified = (s.modified + s.changed).sorted(),
                deleted = (s.removed + s.missing).sorted(),
            )
        }
    }

    /**
     * Stage [paths] (relative to workDir), create a commit with [message],
     * then push. Returns the abbreviated commit hash.
     */
    fun commitAndPush(paths: List<String>, message: String, token: String): String {
        openGit().use { git ->
            val addCmd = git.add()
            paths.forEach { addCmd.addFilepattern(it) }
            addCmd.call()

            val commit = git.commit()
                .setMessage(message)
                .setAuthor("Phone Companion", "phone-companion@local")
                .call()

            git.push()
                .setCredentialsProvider(creds(token))
                .call()

            return commit.abbreviate(8).name()
        }
    }

    /**
     * Pull remote, then commit and push local changes in one operation.
     * If there are uncommitted local changes, stash them first to allow
     * the pull, then reapply.
     */
    fun sync(paths: List<String>, message: String, token: String): String {
        openGit().use { git ->
            val hasLocalChanges = git.status().call().let {
                it.modified.isNotEmpty() || it.changed.isNotEmpty() || it.untracked.isNotEmpty()
            }

            if (hasLocalChanges) {
                git.stashCreate().call()
            }

            git.pull().setCredentialsProvider(creds(token)).call()

            if (hasLocalChanges) {
                runCatching { git.stashApply().call() }.onFailure {
                    // Stash conflict — reset to pulled state and re-stage our files
                    git.reset().setMode(ResetCommand.ResetType.HARD).call()
                    throw GitSyncConflictException("Merge conflict during sync — reset to remote state", it)
                }
            }
        }
        return commitAndPush(paths, message, token)
    }

    /**
     * Returns the log of commits from HEAD back [maxCount] entries.
     * Each entry is a pair of (abbreviated hash, subject line).
     */
    fun log(maxCount: Int = 10): List<Pair<String, String>> {
        openGit().use { git ->
            return git.log().setMaxCount(maxCount).call().map { commit ->
                commit.abbreviate(8).name() to commit.shortMessage
            }
        }
    }

    private fun openGit(): Git {
        check(isInitialized) { "No git repository at $workDir — call clone() first" }
        return Git.open(workDir)
    }
}

class GitSyncConflictException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
