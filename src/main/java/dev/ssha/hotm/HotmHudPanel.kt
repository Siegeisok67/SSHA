package dev.ssha.hotm

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.awt.Color
import java.util.Locale

/** Independent panel renderer inspired by Skyblocker's Commissions widget; no Skyblocker code/assets are bundled. */
internal object HotmHudPanel {
    const val ACCENT = 0xFF00AAAA.toInt()
    const val BACKGROUND = 0xBF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    data class Commission(val name: String, val progress: String, val percent: Double?)
    data class CompactCommission(val name: String, val percent: String)
    data class Plan(val width: Int, val height: Int, val operations: List<Operation>)
    sealed interface Operation {
        data class Fill(val x: Int, val y: Int, val width: Int, val height: Int, val color: Int) : Operation
        data class Text(val text: Component, val x: Int, val y: Int, val color: Int, val shadow: Boolean = false) : Operation
        data class Book(val x: Int, val y: Int) : Operation
    }

    fun commission(text: String): Commission? {
        val clean = plain(text)
        if (!clean.contains(':')) return null
        val name = clean.substringBefore(':').trim()
        val progress = clean.substringAfter(':').trim()
        if (name.isEmpty() || name.equals("Commissions", ignoreCase = true) || progress.isEmpty()) return null
        val percent = if (progress.equals("DONE", ignoreCase = true) || progress.equals("COMPLETE", ignoreCase = true)) {
            100.0
        } else {
            Regex("^([0-9]+(?:\\.[0-9]+)?)%$").matchEntire(progress)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("^([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)$").matchEntire(progress)?.let {
                    val current = it.groupValues[1].replace(",", "").toDoubleOrNull()
                    val required = it.groupValues[2].replace(",", "").toDoubleOrNull()
                    if (current != null && required != null && required > 0.0) current / required * 100.0 else null
                }
        }?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        return Commission(name, progress, percent)
    }

    fun plan(lines: List<Component>, lineHeight: Int, estimates: Map<String, String> = emptyMap(), measure: (Component) -> Int): Plan {
        val commissionHeader = lines.indexOfFirst { plain(it.string).equals("Commissions:", ignoreCase = true) }
        val summary = lines.drop(1).take(if (commissionHeader < 0) lines.size else commissionHeader - 1)
            .map { Component.literal(it.string.trimStart()) }
        val commissions = if (commissionHeader < 0) emptyList() else lines.drop(commissionHeader + 1).mapNotNull { commission(it.string) }
        val manual = if (lines.firstOrNull()?.string?.contains("(Manual)") == true) " (Manual)" else ""
        val title = Component.literal((if (commissions.isEmpty()) "HOTM" else "HOTM & Commissions") + manual)
            .withStyle(ChatFormatting.BOLD)
        fun description(commission: Commission): String {
            val percent = commission.percent?.let { String.format(Locale.US, "%.1f%%", it) } ?: commission.progress
            val eta = estimates[commission.name.uppercase(Locale.ROOT)]
            return commission.name + " §7$percent" + (eta?.let { " §8($it)" } ?: "")
        }
        val width = maxOf(
            150,
            measure(title) + 20,
            (summary.maxOfOrNull(measure) ?: 0) + 12,
            (commissions.maxOfOrNull { measure(Component.literal(description(it))) } ?: 0) + 12,
        )
        val contentTop = lineHeight + 5
        val summaryHeight = summary.size * (lineHeight + 2)
        val separatorHeight = if (commissions.isEmpty()) 0 else 6
        val commissionHeight = lineHeight + 1
        val height = contentTop + summaryHeight + separatorHeight + commissions.size * commissionHeight + 5
        val operations = mutableListOf<Operation>()
        // One-pixel inset corners and a title cut-out on the top border.
        operations.add(Operation.Fill(1, 0, width - 2, height, BACKGROUND))
        operations.add(Operation.Fill(0, 1, 1, height - 2, BACKGROUND))
        operations.add(Operation.Fill(width - 1, 1, 1, height - 2, BACKGROUND))
        val borderY = 2 + lineHeight / 2
        operations.add(Operation.Fill(2, borderY, 3, 1, ACCENT))
        val titleEnd = 8 + measure(title) + 3
        operations.add(Operation.Fill(titleEnd, borderY, width - titleEnd - 2, 1, ACCENT))
        operations.add(Operation.Fill(1, borderY + 1, 1, height - borderY - 3, ACCENT))
        operations.add(Operation.Fill(width - 2, borderY + 1, 1, height - borderY - 3, ACCENT))
        operations.add(Operation.Fill(2, height - 2, width - 4, 1, ACCENT))
        operations.add(Operation.Text(title, 8, 2, ACCENT))
        var y = contentTop
        summary.forEach {
            operations.add(Operation.Text(it, 6, y, WHITE))
            y += lineHeight + 2
        }
        if (commissions.isNotEmpty()) {
            operations.add(Operation.Fill(6, y + 1, width - 12, 1, 0x5500AAAA))
            y += separatorHeight
        }
        commissions.forEach { commission ->
            operations.add(Operation.Text(Component.literal(description(commission)), 6, y, WHITE))
            y += commissionHeight
        }
        return Plan(width, height, operations)
    }

    fun commissions(rows: List<CompactCommission>, lineHeight: Int, measure: (Component) -> Int): Plan {
        val title = Component.literal("Commissions").withStyle(ChatFormatting.BOLD)
        val renderedRows = rows.map { Component.literal("${it.name} §7${it.percent}") }
        val width = maxOf(measure(title) + 18, (renderedRows.maxOfOrNull(measure) ?: 0) + 12, 86)
        val height = lineHeight + 9 + renderedRows.size * (lineHeight + 1)
        val borderY = 2 + lineHeight / 2
        val titleEnd = 8 + measure(title) + 3
        val operations = mutableListOf<Operation>(
            Operation.Fill(1, 0, width - 2, height, BACKGROUND),
            Operation.Fill(0, 1, 1, height - 2, BACKGROUND),
            Operation.Fill(width - 1, 1, 1, height - 2, BACKGROUND),
            Operation.Fill(2, borderY, 3, 1, ACCENT),
            Operation.Fill(titleEnd, borderY, width - titleEnd - 2, 1, ACCENT),
            Operation.Fill(1, borderY + 1, 1, height - borderY - 3, ACCENT),
            Operation.Fill(width - 2, borderY + 1, 1, height - borderY - 3, ACCENT),
            Operation.Fill(2, height - 2, width - 4, 1, ACCENT),
            Operation.Text(title, 8, 2, ACCENT),
        )
        renderedRows.forEachIndexed { index, row -> operations.add(Operation.Text(row, 6, lineHeight + 5 + index * (lineHeight + 1), WHITE)) }
        return Plan(width, height, operations)
    }

    fun compact(titleText: String, rows: List<Component>, lineHeight: Int, measure: (Component) -> Int): Plan {
        val title = Component.literal(titleText).withStyle(ChatFormatting.BOLD)
        val width = maxOf(measure(title) + 18, (rows.maxOfOrNull(measure) ?: 0) + 12)
        val height = lineHeight + 9 + rows.size * (lineHeight + 1)
        val borderY = 2 + lineHeight / 2
        val titleEnd = 8 + measure(title) + 3
        val operations = mutableListOf<Operation>(
            Operation.Fill(1, 0, width - 2, height, BACKGROUND),
            Operation.Fill(0, 1, 1, height - 2, BACKGROUND),
            Operation.Fill(width - 1, 1, 1, height - 2, BACKGROUND),
            Operation.Fill(2, borderY, 3, 1, ACCENT),
            Operation.Fill(titleEnd, borderY, width - titleEnd - 2, 1, ACCENT),
            Operation.Fill(1, borderY + 1, 1, height - borderY - 3, ACCENT),
            Operation.Fill(width - 2, borderY + 1, 1, height - borderY - 3, ACCENT),
            Operation.Fill(2, height - 2, width - 4, 1, ACCENT),
            Operation.Text(title, 8, 2, ACCENT),
        )
        rows.forEachIndexed { index, row -> operations.add(Operation.Text(row, 6, lineHeight + 5 + index * (lineHeight + 1), WHITE)) }
        return Plan(width, height, operations)
    }

    fun progressColor(percent: Double): Int = Color.HSBtoRGB((percent.coerceIn(0.0, 100.0) / 300.0).toFloat(), 1f, 1f)
    private fun plain(text: String): String = text.replace(Regex("§."), "").trim()
}
