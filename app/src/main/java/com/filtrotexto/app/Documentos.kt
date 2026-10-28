package com.filtrotexto.app

/** Extracción de texto de documentos de Office, sin librerías externas. */
object Documentos {

    // Texto (<w:t>), tabulaciones, saltos de línea y fin de párrafo de un DOCX.
    private val PIEZAS = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|<w:tab\s*/>|<w:(?:br|cr)\b[^>]*/>|</w:p>""")
    private val ENTIDAD = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")

    /** Devuelve el texto de un word/document.xml, con un salto de línea por párrafo. */
    fun textoDeDocx(xml: String): String {
        val sb = StringBuilder()
        for (m in PIEZAS.findAll(xml)) {
            val pieza = m.value
            when {
                pieza.startsWith("<w:tab") -> sb.append('\t')
                pieza.startsWith("<w:br") || pieza.startsWith("<w:cr") -> sb.append('\n')
                pieza.startsWith("</w:p") -> sb.append('\n')
                else -> sb.append(decodificar(m.groupValues[1]))
            }
        }
        return sb.toString()
    }

    /** Convierte &amp; &lt; &#233; &#xE9; etc. en los caracteres que representan. */
    fun decodificar(s: String): String {
        if (!s.contains('&')) return s
        return ENTIDAD.replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val cp = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                    if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
                }
            }
        }
    }
}
