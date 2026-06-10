package com.robo.phonecompanion.data.parser

import com.robo.phonecompanion.data.model.ByteOrder
import com.robo.phonecompanion.data.model.Dbc
import com.robo.phonecompanion.data.model.DbcMessage
import com.robo.phonecompanion.data.model.DbcSignal

object DbcParser {

    private val MESSAGE_RE = Regex("""^BO_\s+(\d+)\s+(\w+)\s*:\s*(\d+)\s+(\S+)""")

    // SG_ Name [mux_indicator] : startBit|length@byteOrder valueType (factor,offset) [min|max] "unit" receivers
    // mux_indicator: "M" = multiplexer selector, "m<N>" = muxed at slot N; absent for plain signals
    private val SIGNAL_RE = Regex(
        """^\s+SG_\s+(\w+)\s+(\w+\s+)?:\s+(\d+)\|(\d+)@([01])([+-])\s+""" +
            """\(([^,]+),([^)]+)\)\s+\[([^|]*)\|([^\]]*)\]\s+"([^"]*)"\s*(.*)"""
    )

    private val COMMENT_MSG_RE = Regex("""^CM_\s+BO_\s+(\d+)\s+"((?:[^"\\]|\\.)*)"\s*;""", RegexOption.MULTILINE)
    private val COMMENT_SIG_RE = Regex("""^CM_\s+SG_\s+(\d+)\s+(\w+)\s+"((?:[^"\\]|\\.)*)"\s*;""", RegexOption.MULTILINE)
    private val VAL_RE = Regex("""^VAL_\s+(\d+)\s+(\w+)\s+(.*?)\s*;""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
    private val VAL_ENTRY_RE = Regex("""(\d+)\s+"([^"]*)" """)
    private val VERSION_RE = Regex("""^VERSION\s+"([^"]*)"""")

    fun parse(text: String): Dbc {
        val lines = text.lines()
        var version = ""
        val messages = mutableMapOf<Int, DbcMessage>()
        var currentMsg: DbcMessage? = null

        for (line in lines) {
            VERSION_RE.find(line)?.let { version = it.groupValues[1]; return@let }

            MESSAGE_RE.find(line)?.let { m ->
                currentMsg?.let { messages[it.rawId] = it }
                val rawId = m.groupValues[1].toInt()
                currentMsg = DbcMessage(
                    rawId = rawId,
                    name = m.groupValues[2],
                    dlc = m.groupValues[3].toInt(),
                    transmitter = m.groupValues[4],
                )
                return@let
            }

            SIGNAL_RE.find(line)?.let { s ->
                val msg = currentMsg ?: return@let
                val g = s.groupValues
                val muxIndicator = g[2].trim().takeIf { it.isNotEmpty() }
                val signal = DbcSignal(
                    name = g[1],
                    startBit = g[3].toInt(),
                    length = g[4].toInt(),
                    byteOrder = if (g[5] == "1") ByteOrder.INTEL else ByteOrder.MOTOROLA,
                    signed = g[6] == "-",
                    factor = g[7].trim().toDouble(),
                    offset = g[8].trim().toDouble(),
                    min = g[9].trim().toDoubleOrNull() ?: 0.0,
                    max = g[10].trim().toDoubleOrNull() ?: 0.0,
                    unit = g[11],
                    muxIndicator = muxIndicator,
                )
                currentMsg = msg.copy(signals = msg.signals + signal)
                return@let
            }

            // Flush current message on blank line or next top-level keyword
            if (currentMsg != null && (line.isBlank() || line.startsWith("BO_") ||
                    line.startsWith("CM_") || line.startsWith("VAL_"))
            ) {
                messages[currentMsg!!.rawId] = currentMsg!!
                if (!line.startsWith("BO_")) currentMsg = null
            }
        }
        currentMsg?.let { messages[it.rawId] = it }

        applyComments(lines, messages)
        applyValueDescriptions(lines, messages)

        return Dbc(version = version, messages = messages)
    }

    private fun applyComments(lines: List<String>, messages: MutableMap<Int, DbcMessage>) {
        val joined = lines.joinToString("\n")

        COMMENT_MSG_RE.findAll(joined).forEach { m ->
            val rawId = m.groupValues[1].toInt()
            messages[rawId]?.let { messages[rawId] = it.copy(comment = m.groupValues[2]) }
        }

        COMMENT_SIG_RE.findAll(joined).forEach { m ->
            val rawId = m.groupValues[1].toInt()
            val sigName = m.groupValues[2]
            val comment = m.groupValues[3]
            messages[rawId]?.let { msg ->
                val updated = msg.signals.map { s ->
                    if (s.name == sigName) s.copy(comment = comment) else s
                }
                messages[rawId] = msg.copy(signals = updated)
            }
        }
    }

    private fun applyValueDescriptions(lines: List<String>, messages: MutableMap<Int, DbcMessage>) {
        val joined = lines.joinToString("\n")

        VAL_RE.findAll(joined).forEach { m ->
            val rawId = m.groupValues[1].toInt()
            val sigName = m.groupValues[2]
            val entries = VAL_ENTRY_RE.findAll(m.groupValues[3] + " ")
                .associate { it.groupValues[1].toLong() to it.groupValues[2] }

            messages[rawId]?.let { msg ->
                val updated = msg.signals.map { s ->
                    if (s.name == sigName) s.copy(valueDescriptions = entries) else s
                }
                messages[rawId] = msg.copy(signals = updated)
            }
        }
    }
}
