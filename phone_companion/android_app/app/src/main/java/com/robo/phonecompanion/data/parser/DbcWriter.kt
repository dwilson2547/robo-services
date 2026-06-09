package com.robo.phonecompanion.data.parser

import com.robo.phonecompanion.data.model.ByteOrder
import com.robo.phonecompanion.data.model.Dbc
import com.robo.phonecompanion.data.model.DbcMessage
import com.robo.phonecompanion.data.model.DbcSignal

object DbcWriter {

    fun write(dbc: Dbc): String = buildString {
        appendLine("""VERSION "${dbc.version}"""")
        appendLine()
        appendLine("NS_ :")
        appendLine()
        appendLine("BS_:")
        appendLine()
        appendLine("BU_:")
        appendLine()

        for (msg in dbc.messages.values.sortedBy { it.rawId }) {
            appendMessage(msg)
            appendLine()
        }

        // Comments block
        for (msg in dbc.messages.values.sortedBy { it.rawId }) {
            msg.comment?.let {
                appendLine("""CM_ BO_ ${msg.rawId} "$it";""")
            }
            for (sig in msg.signals) {
                sig.comment?.let {
                    appendLine("""CM_ SG_ ${msg.rawId} ${sig.name} "$it";""")
                }
            }
        }

        // Value descriptions block
        for (msg in dbc.messages.values.sortedBy { it.rawId }) {
            for (sig in msg.signals) {
                if (sig.valueDescriptions.isNotEmpty()) {
                    val entries = sig.valueDescriptions.entries
                        .sortedBy { it.key }
                        .joinToString(" ") { """${it.key} "${it.value}"""" }
                    appendLine("VAL_ ${msg.rawId} ${sig.name} $entries ;")
                }
            }
        }
    }

    private fun StringBuilder.appendMessage(msg: DbcMessage) {
        appendLine("BO_ ${msg.rawId} ${msg.name}: ${msg.dlc} ${msg.transmitter}")
        for (sig in msg.signals) {
            appendSignal(sig)
        }
    }

    private fun StringBuilder.appendSignal(sig: DbcSignal) {
        val byteOrderChar = if (sig.byteOrder == ByteOrder.INTEL) "1" else "0"
        val signChar = if (sig.signed) "-" else "+"
        val factor = sig.factor.toBigDecimal().stripTrailingZeros().toPlainString()
        val offset = sig.offset.toBigDecimal().stripTrailingZeros().toPlainString()
        val min = sig.min.toBigDecimal().stripTrailingZeros().toPlainString()
        val max = sig.max.toBigDecimal().stripTrailingZeros().toPlainString()
        appendLine(
            """ SG_ ${sig.name} : ${sig.startBit}|${sig.length}@$byteOrderChar$signChar """ +
                """($factor,$offset) [$min|$max] "${sig.unit}" Vector__XXX"""
        )
    }
}
