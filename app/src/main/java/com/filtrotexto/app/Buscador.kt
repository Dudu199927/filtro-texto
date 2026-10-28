package com.filtrotexto.app

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.io.Writer
import java.nio.charset.Charset
import java.text.NumberFormat
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Qué buscar y cómo. Las palabras ya vienen en minúsculas si no se distinguen mayúsculas. */
class Parametros(
    val palabras: List<String>,
    val excluidas: List<String>,
    val todas: Boolean,
    val mayus: Boolean,
    val latin1: Boolean
)

/** Formato que la app todavía no sabe leer. El mensaje se muestra tal cual al usuario. */
class FormatoNoAdmitido(mensaje: String) : Exception(mensaje)

/**
 * Un archivo comprimido que se lee por posición (RAR, 7z...). Lo implementa la parte de Android;
 * así el buscador no depende de ninguna librería nativa.
 * Los errores de contraseña se informan lanzando [FormatoNoAdmitido].
 */
interface ArchivoRar : Closeable {
    val cantidad: Int
    fun ruta(i: Int): String
    fun esDirectorio(i: Int): Boolean
    /** Escribe el contenido de la entrada en [salida] y la cierra. */
    fun extraer(i: Int, salida: java.io.OutputStream)
}

/**
 * Busca en un archivo de texto, o dentro de un ZIP / DOCX (incluso anidados),
 * y escribe en [escritor] las líneas que coinciden. No depende de Android.
 * Cuando el archivo es un contenedor, cada línea sale con el nombre de su archivo de origen.
 */
class Buscador(
    private val p: Parametros,
    private val escritor: Writer,
    private val bytesLeidos: () -> Long,
    private val total: Long,
    private val cancelado: () -> Boolean,
    private val alAvanzar: (Int, String) -> Unit,
    private val previaMax: Int = 200,
    private val previaAncho: Int = 400,
    private val abrirRar: ((File) -> ArchivoRar)? = null,
    private val carpetaTemp: File? = null
) {
    private companion object {
        const val CABECERA = 8192
        const val MAX_EN_MEMORIA = 64 * 1024 * 1024
        const val MAX_ANIDADO = 3
        val PARTES_DOCX = Regex("""^word/(document|footnotes|endnotes|header\d*|footer\d*)\.xml$""", RegexOption.IGNORE_CASE)
    }

    var lineas = 0L
        private set
    var coincidencias = 0L
        private set
    var archivosLeidos = 0
        private set
    var enArchivo = false
        private set
    var interrumpido = false
        private set
    var cantOmitidos = 0
        private set

    /** Nombres de los primeros archivos que no se pudieron leer (no son de texto ni DOCX). */
    val omitidos = ArrayList<String>()

    /** Primeras coincidencias, para la vista previa. */
    val previa = ArrayList<String>()

    private val nf = NumberFormat.getInstance()
    private var rarTotal = 0
    private var rarHecho = 0
    private var copiando = false
    private var ultimoAviso = System.nanoTime()

    // ---------------------------------------------------------------- entrada principal

    /** [entrada] debe permitir mark/reset. [nombre] es el nombre del archivo elegido. */
    fun procesar(entrada: BufferedInputStream, nombre: String) {
        val cab = ByteArray(CABECERA)
        entrada.mark(CABECERA)
        val n = leerCabecera(entrada, cab)
        entrada.reset()

        when {
            esZip(cab, n) -> {
                enArchivo = true
                buscarEnZip(entrada, "", nombre, 0)
            }
            esRarOSieteZ(cab, n) -> {
                enArchivo = true
                val f = copiarATemporal(entrada) ?: return
                try {
                    buscarEnRar(f, "", nombre, 0)
                } finally {
                    f.delete()
                }
            }
            empieza(cab, n, 0x25, 0x50, 0x44, 0x46) ->
                throw FormatoNoAdmitido("Los archivos PDF todavía no se admiten.")
            empieza(cab, n, 0x1F, 0x8B) ->
                throw FormatoNoAdmitido("Los archivos GZ todavía no se admiten.")
            else -> {
                val cs = when {
                    esUtf16(cab, n) -> Charsets.UTF_16
                    p.latin1 -> Charsets.ISO_8859_1
                    else -> Charsets.UTF_8
                }
                buscarTexto(BufferedReader(InputStreamReader(entrada, cs), 1 shl 20), null)
            }
        }
    }

    // ---------------------------------------------------------------- texto

    private fun buscarTexto(lector: BufferedReader, etiqueta: String?) {
        while (true) {
            val linea = lector.readLine() ?: break
            lineas++

            val base = if (p.mayus) linea else linea.lowercase()
            val encontrada =
                if (p.todas) p.palabras.all { base.contains(it) }
                else p.palabras.any { base.contains(it) }

            if (encontrada && p.excluidas.none { base.contains(it) }) {
                val salida = if (etiqueta == null) linea else "[$etiqueta] $linea"
                escritor.write(salida)
                escritor.write("\n")
                coincidencias++
                if (previa.size < previaMax) {
                    previa.add(if (salida.length > previaAncho) salida.take(previaAncho) + "…" else salida)
                }
            }

            if ((lineas and 0xFFFL) == 0L && revisar()) return
        }
    }

    // ---------------------------------------------------------------- zip y docx

    /**
     * [ruta] es el camino del contenedor dentro del archivo elegido ("" si es el archivo mismo);
     * [nombreVisible] es cómo se llama este contenedor cuando es un DOCX.
     */
    private fun buscarEnZip(entrada: InputStream, ruta: String, nombreVisible: String, profundidad: Int) {
        val v = nombreVisible.lowercase()
        var ooxml = v.endsWith(".docx") || v.endsWith(".docm") || v.endsWith(".dotx")
        var partesDocx = 0

        // Los nombres sin la marca UTF-8 se leen como ISO-8859-1, que nunca falla.
        val zis = ZipInputStream(entrada, Charsets.ISO_8859_1)
        try {
            while (true) {
                if (interrumpido || cancelado()) {
                    interrumpido = true
                    break
                }
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue

                val nombreE = e.name
                val minus = nombreE.lowercase()
                val etiqueta = if (ruta.isEmpty()) nombreE else "$ruta/$nombreE"

                if (minus == "[content_types].xml" || minus.startsWith("word/")) ooxml = true

                if (ooxml) {
                    // Un documento de Office: solo interesa el texto del documento.
                    if (PARTES_DOCX.matches(nombreE)) {
                        val bytes = leerLimitado(zis)
                        if (bytes == null) {
                            omitir("$nombreVisible (demasiado grande)")
                        } else {
                            val texto = Documentos.textoDeDocx(String(bytes, Charsets.UTF_8))
                            buscarTexto(BufferedReader(StringReader(texto)), nombreVisible)
                            partesDocx++
                        }
                        avanzar(false)
                    }
                    continue
                }

                if (minus.endsWith(".rar") || minus.endsWith(".7z")) {
                    if (profundidad >= MAX_ANIDADO || abrirRar == null || carpetaTemp == null) {
                        omitir(etiqueta)
                        continue
                    }
                    val bytes = leerLimitado(zis)
                    if (bytes == null) {
                        omitir("$etiqueta (demasiado grande)")
                    } else {
                        val tmp = File.createTempFile("rar", ".tmp", carpetaTemp)
                        try {
                            FileOutputStream(tmp).use { it.write(bytes) }
                            buscarEnRar(tmp, etiqueta, etiqueta, profundidad + 1)
                        } finally {
                            tmp.delete()
                        }
                    }
                    avanzar(false)
                    continue
                }

                val esAnidado = minus.endsWith(".zip") || minus.endsWith(".docx") ||
                    minus.endsWith(".docm") || minus.endsWith(".dotx")
                if (esAnidado) {
                    if (profundidad >= MAX_ANIDADO) {
                        omitir("$etiqueta (anidado demasiado profundo)")
                        continue
                    }
                    val bytes = leerLimitado(zis)
                    if (bytes == null) {
                        omitir("$etiqueta (demasiado grande)")
                    } else if (esZip(bytes, minOf(bytes.size, 4))) {
                        buscarEnZip(ByteArrayInputStream(bytes), etiqueta, etiqueta, profundidad + 1)
                    } else {
                        omitir(etiqueta)
                    }
                    avanzar(false)
                    continue
                }

                // Cualquier otro archivo: se lee si parece texto, si no se omite.
                val b = BufferedInputStream(NoCierra(zis), 1 shl 16)
                val cab = ByteArray(CABECERA)
                b.mark(CABECERA)
                val n = leerCabecera(b, cab)
                b.reset()
                val cs = charsetDeEntrada(cab, n)
                if (cs == null) {
                    omitir(etiqueta)
                    continue
                }
                archivosLeidos++
                buscarTexto(BufferedReader(InputStreamReader(b, cs)), etiqueta)
                avanzar(false)
            }
        } catch (e: ZipException) {
            if (e.message?.contains("encrypted", ignoreCase = true) == true) {
                throw FormatoNoAdmitido("El archivo ZIP tiene contraseña; todavía no se admiten.")
            }
            throw e
        } finally {
            try {
                zis.close()
            } catch (e2: Exception) {
                // no es grave
            }
        }

        if (ooxml) {
            if (partesDocx > 0) archivosLeidos++ else omitir(nombreVisible)
        }
    }

    // ---------------------------------------------------------------- rar y 7z

    /** Recorre un RAR / 7z ya guardado en disco. [ruta] es su camino dentro del archivo elegido. */
    private fun buscarEnRar(archivo: File, ruta: String, nombreVisible: String, profundidad: Int) {
        val abrir = abrirRar
        val carpeta = carpetaTemp
        if (abrir == null || carpeta == null) {
            throw FormatoNoAdmitido("Este formato (RAR / 7z) no está disponible en esta versión.")
        }
        val rar = abrir(archivo)
        try {
            val cant = rar.cantidad
            for (i in 0 until cant) {
                if (interrumpido || cancelado()) {
                    interrumpido = true
                    break
                }
                if (profundidad == 0) {
                    rarTotal = cant
                    rarHecho = i
                }
                if (rar.esDirectorio(i)) continue

                val nombreE = rar.ruta(i).replace('\\', '/')
                val etiqueta = if (ruta.isEmpty()) nombreE else "$ruta/$nombreE"
                val tmp = File.createTempFile("rar", ".tmp", carpeta)
                try {
                    rar.extraer(i, FileOutputStream(tmp))
                    analizarExtraido(tmp, etiqueta, nombreE, profundidad)
                } catch (e: FormatoNoAdmitido) {
                    throw e
                } catch (e: Exception) {
                    omitir("$etiqueta (error al leer)")
                } finally {
                    tmp.delete()
                }
                avanzar(false)
            }
        } finally {
            try {
                rar.close()
            } catch (e: Exception) {
                // no es grave
            }
            if (profundidad == 0) {
                rarTotal = 0
                rarHecho = 0
            }
        }
    }

    /** Decide qué hacer con un archivo sacado de un RAR / 7z: otro contenedor, texto, u omitirlo. */
    private fun analizarExtraido(f: File, etiqueta: String, nombreE: String, profundidad: Int) {
        val minus = nombreE.lowercase()
        BufferedInputStream(FileInputStream(f), 1 shl 16).use { b ->
            val cab = ByteArray(CABECERA)
            b.mark(CABECERA)
            val n = leerCabecera(b, cab)
            b.reset()

            val esDocOZip = minus.endsWith(".zip") || minus.endsWith(".docx") ||
                minus.endsWith(".docm") || minus.endsWith(".dotx")

            when {
                esDocOZip && esZip(cab, n) ->
                    if (profundidad >= MAX_ANIDADO) omitir("$etiqueta (anidado demasiado profundo)")
                    else buscarEnZip(b, etiqueta, etiqueta, profundidad + 1)
                esRarOSieteZ(cab, n) ->
                    if (profundidad >= MAX_ANIDADO) omitir("$etiqueta (anidado demasiado profundo)")
                    else buscarEnRar(f, etiqueta, etiqueta, profundidad + 1)
                else -> {
                    val cs = charsetDeEntrada(cab, n)
                    if (cs == null) {
                        omitir(etiqueta)
                    } else {
                        archivosLeidos++
                        buscarTexto(BufferedReader(InputStreamReader(b, cs)), etiqueta)
                    }
                }
            }
        }
    }

    /** Guarda la entrada completa en un archivo temporal (RAR y 7z necesitan acceso por posición). */
    private fun copiarATemporal(entrada: InputStream): File? {
        val carpeta = carpetaTemp
        if (abrirRar == null || carpeta == null) {
            throw FormatoNoAdmitido("Este formato (RAR / 7z) no está disponible en esta versión.")
        }
        val tmp = File.createTempFile("rar", ".tmp", carpeta)
        try {
            copiando = true
            val buffer = ByteArray(1 shl 16)
            FileOutputStream(tmp).use { salida ->
                while (true) {
                    val n = entrada.read(buffer)
                    if (n < 0) break
                    salida.write(buffer, 0, n)
                    if (cancelado()) {
                        interrumpido = true
                        break
                    }
                    avanzar(false)
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            copiando = false
        }
        if (interrumpido) {
            tmp.delete()
            return null
        }
        return tmp
    }

    // ---------------------------------------------------------------- utilidades

    /** Cada tanto avisa del avance y revisa si se canceló. Devuelve true si hay que parar. */
    private fun revisar(): Boolean {
        if (cancelado()) {
            interrumpido = true
            return true
        }
        avanzar(false)
        return false
    }

    private fun avanzar(forzar: Boolean) {
        val ahora = System.nanoTime()
        if (!forzar && ahora - ultimoAviso < 250_000_000L) return
        ultimoAviso = ahora

        val pct = when {
            rarTotal > 0 -> (rarHecho * 1000L / rarTotal).toInt().coerceIn(0, 1000)
            total > 0 -> (bytesLeidos() * 1000 / total).toInt().coerceIn(0, 1000)
            else -> 0
        }
        val c = nf.format(coincidencias)
        val base = when {
            copiando -> "Preparando el archivo… ${"%.1f".format(pct / 10.0)} %"
            rarTotal > 0 -> "Procesando… archivo ${nf.format(rarHecho + 1)} de ${nf.format(rarTotal)}  |  coincidencias: $c"
            total > 0 -> "Procesando… ${"%.1f".format(pct / 10.0)} %  |  coincidencias: $c"
            else -> "Procesando…  |  coincidencias: $c"
        }
        alAvanzar(pct, if (enArchivo) "$base  |  archivos: ${nf.format(archivosLeidos)}" else base)
    }

    private fun omitir(nombre: String) {
        cantOmitidos++
        if (omitidos.size < 20) omitidos.add(nombre)
    }

    /** Lee el archivo actual completo en memoria; null si supera el límite. */
    private fun leerLimitado(entrada: InputStream): ByteArray? {
        val salida = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = entrada.read(buffer)
            if (n < 0) break
            if (salida.size() + n > MAX_EN_MEMORIA) return null
            salida.write(buffer, 0, n)
        }
        return salida.toByteArray()
    }

    private fun leerCabecera(entrada: InputStream, cab: ByteArray): Int {
        var leido = 0
        while (leido < cab.size) {
            val n = entrada.read(cab, leido, cab.size - leido)
            if (n < 0) break
            leido += n
        }
        return leido
    }

    private fun empieza(cab: ByteArray, n: Int, vararg firma: Int): Boolean {
        if (n < firma.size) return false
        for (i in firma.indices) {
            if ((cab[i].toInt() and 0xFF) != firma[i]) return false
        }
        return true
    }

    private fun esZip(cab: ByteArray, n: Int): Boolean =
        empieza(cab, n, 0x50, 0x4B, 0x03, 0x04) || empieza(cab, n, 0x50, 0x4B, 0x05, 0x06)

    private fun esRarOSieteZ(cab: ByteArray, n: Int): Boolean =
        empieza(cab, n, 0x52, 0x61, 0x72, 0x21) || empieza(cab, n, 0x37, 0x7A, 0xBC, 0xAF)

    private fun esUtf16(cab: ByteArray, n: Int): Boolean =
        empieza(cab, n, 0xFF, 0xFE) || empieza(cab, n, 0xFE, 0xFF)

    /** Charset para leer un archivo de un ZIP como texto, o null si parece binario. */
    private fun charsetDeEntrada(cab: ByteArray, n: Int): Charset? {
        if (esUtf16(cab, n)) return Charsets.UTF_16
        for (i in 0 until n) {
            if (cab[i].toInt() == 0) return null
        }
        return if (p.latin1) Charsets.ISO_8859_1 else Charsets.UTF_8
    }
}

/** Evita que un lector cierre el ZIP completo al terminar con uno de sus archivos. */
private class NoCierra(entrada: InputStream) : FilterInputStream(entrada) {
    override fun close() {
        // lo cierra quien lo abrió
    }
}
