package com.robo.phonecompanion.data.repository

import com.robo.phonecompanion.data.model.Dbc
import com.robo.phonecompanion.data.parser.DbcParser
import com.robo.phonecompanion.data.parser.DbcWriter
import java.io.File

class DbcRepository(private val dbcsDir: File) {

    fun loadAll(): Map<String, Dbc> {
        if (!dbcsDir.exists()) return emptyMap()
        return dbcsDir.listFiles { f -> f.extension == "dbc" }
            ?.associate { f ->
                f.nameWithoutExtension to DbcParser.parse(f.readText())
            }
            ?: emptyMap()
    }

    fun load(id: String): Dbc? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching { DbcParser.parse(file.readText()) }.getOrNull()
    }

    fun save(id: String, dbc: Dbc) {
        dbcsDir.mkdirs()
        fileFor(id).writeText(DbcWriter.write(dbc))
    }

    fun saveRaw(id: String, content: String) {
        dbcsDir.mkdirs()
        fileFor(id).writeText(content)
    }

    fun exists(id: String): Boolean = fileFor(id).exists()

    fun sidecarFor(id: String): SidecarRepository =
        SidecarRepository(File(dbcsDir, "$id.sidecar.json"))

    fun listIds(): List<String> {
        if (!dbcsDir.exists()) return emptyList()
        return dbcsDir.listFiles { f -> f.extension == "dbc" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    private fun fileFor(id: String) = File(dbcsDir, "$id.dbc")
}
