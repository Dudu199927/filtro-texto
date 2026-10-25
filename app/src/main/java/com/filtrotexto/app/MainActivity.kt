package com.filtrotexto.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.NumberFormat
import kotlin.concurrent.thread

/**
 * Filtro de texto para archivos grandes.
 *
 * - Lee el archivo línea por línea (streaming), por lo que casi no usa memoria
 *   aunque el archivo pese varios GB.
 * - Los resultados se guardan primero en un archivo temporal y se muestra una
 *   vista previa; recién al tocar "Guardar .txt" se exportan.
 * - Recibe archivos desde "Compartir" y "Abrir con" de otras apps.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_ABRIR = 1
        const val REQ_GUARDAR = 2
        const val PREVIA_MAX = 200
        const val PREVIA_ANCHO = 400
    }

    private class Busqueda(
        val palabras: List<String>,
        val todas: Boolean,
        val mayus: Boolean,
        val excluidas: List<String>,
        val latin1: Boolean
    )

    private lateinit var btnElegir: Button
    private lateinit var btnBuscar: Button
    private lateinit var btnCancelar: Button
    private lateinit var btnGuardar: Button
    private lateinit var btnDescartar: Button
    private lateinit var btnCopiar: Button
    private lateinit var llAcciones: LinearLayout
    private lateinit var tvArchivo: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvPreviaTitulo: TextView
    private lateinit var tvPrevia: TextView
    private lateinit var etBuscar: EditText
    private lateinit var etExcluir: EditText
    private lateinit var rbTodas: RadioButton
    private lateinit var cbMayus: CheckBox
    private lateinit var cbLatin1: CheckBox
    private lateinit var barra: ProgressBar

    private var archivoUri: Uri? = null
    private var archivoNombre = ""
    private var archivoTam = -1L

    private var resultadoTemp: File? = null
    private var resultadoCoinc = 0L

    @Volatile private var cancelar = false
    @Volatile private var trabajando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        limpiarTemporales()

        btnElegir = findViewById(R.id.btnElegir)
        btnBuscar = findViewById(R.id.btnBuscar)
        btnCancelar = findViewById(R.id.btnCancelar)
        btnGuardar = findViewById(R.id.btnGuardar)
        btnDescartar = findViewById(R.id.btnDescartar)
        btnCopiar = findViewById(R.id.btnCopiar)
        llAcciones = findViewById(R.id.llAcciones)
        tvArchivo = findViewById(R.id.tvArchivo)
        tvEstado = findViewById(R.id.tvEstado)
        tvPreviaTitulo = findViewById(R.id.tvPreviaTitulo)
        tvPrevia = findViewById(R.id.tvPrevia)
        etBuscar = findViewById(R.id.etBuscar)
        etExcluir = findViewById(R.id.etExcluir)
        rbTodas = findViewById(R.id.rbTodas)
        cbMayus = findViewById(R.id.cbMayus)
        cbLatin1 = findViewById(R.id.cbLatin1)
        barra = findViewById(R.id.barra)

        btnElegir.setOnClickListener { elegirArchivo() }
        btnBuscar.setOnClickListener { alPulsarBuscar() }
        btnGuardar.setOnClickListener { alPulsarGuardar() }
        btnCopiar.setOnClickListener { copiarPrevia() }
        btnDescartar.setOnClickListener {
            descartarResultado()
            barra.progress = 0
            barra.visibility = View.INVISIBLE
            tvEstado.text = "Resultados descartados."
        }
        btnCancelar.setOnClickListener {
            cancelar = true
            btnCancelar.isEnabled = false
            tvEstado.text = "Cancelando…"
        }

        manejarIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        manejarIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) resultadoTemp?.delete()
    }

    // ---------------------------------------------------------------- elegir / recibir archivo

    private fun elegirArchivo() {
        if (trabajando) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, REQ_ABRIR)
    }

    /** Archivo recibido desde "Compartir" o "Abrir con" en otra app. */
    private fun manejarIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> uriCompartida(i)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri == null) {
            if (i.action == Intent.ACTION_SEND) aviso("Compartí un archivo de texto, no un texto suelto")
            return
        }
        if (trabajando) {
            aviso("Hay una operación en curso. Esperá a que termine.")
            return
        }
        cargarArchivo(uri)
    }

    @Suppress("DEPRECATION")
    private fun uriCompartida(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun cargarArchivo(uri: Uri) {
        descartarResultado()
        archivoUri = uri
        archivoNombre = uri.lastPathSegment ?: "archivo"
        archivoTam = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iNombre = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iTam = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iNombre >= 0 && !c.isNull(iNombre)) archivoNombre = c.getString(iNombre)
                    if (iTam >= 0 && !c.isNull(iTam)) archivoTam = c.getLong(iTam)
                }
            }
        } catch (e: Exception) {
            // Si no se puede leer el nombre o el tamaño, se sigue igual.
        }
        val tam = if (archivoTam >= 0) " (${formatoTamano(archivoTam)})" else ""
        tvArchivo.text = "Archivo cargado: $archivoNombre$tam"
        tvEstado.text = ""
        barra.progress = 0
        barra.visibility = View.INVISIBLE
    }

    @Deprecated("Se mantiene para no depender de librerías externas")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_ABRIR -> cargarArchivo(uri)
            REQ_GUARDAR -> guardar(uri)
        }
    }

    // ---------------------------------------------------------------- buscar

    private fun alPulsarBuscar() {
        if (trabajando) return
        val entrada = archivoUri
        if (entrada == null) {
            aviso("Primero elegí un archivo")
            return
        }
        val mayus = cbMayus.isChecked
        val palabras = separar(etBuscar.text.toString(), mayus)
        if (palabras.isEmpty()) {
            aviso("Escribí al menos una palabra a buscar")
            return
        }
        val b = Busqueda(
            palabras = palabras,
            todas = rbTodas.isChecked,
            mayus = mayus,
            excluidas = separar(etExcluir.text.toString(), mayus),
            latin1 = cbLatin1.isChecked
        )
        descartarResultado()
        iniciar(entrada, b)
    }

    private fun separar(texto: String, mayus: Boolean): List<String> =
        texto.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (mayus) it else it.lowercase() }

    private fun iniciar(entrada: Uri, b: Busqueda) {
        val temp: File
        try {
            temp = File.createTempFile("resultados", ".tmp", cacheDir)
        } catch (e: IOException) {
            aviso("No se pudo crear un archivo temporal: ${e.message}")
            return
        }
        trabajando = true
        cancelar = false
        pantallaOcupada(true, archivoTam <= 0, "Procesando…")
        val total = archivoTam
        thread(name = "buscador") { buscar(entrada, temp, b, total) }
    }

    private fun buscar(entrada: Uri, temp: File, b: Busqueda, total: Long) {
        val inicio = System.nanoTime()
        val nf = NumberFormat.getInstance()
        val previa = ArrayList<String>()
        var lineas = 0L
        var coinc = 0L
        var error: String? = null
        var cancelado = false

        try {
            val flujoEntrada = contentResolver.openInputStream(entrada)
                ?: throw IOException("No se pudo abrir el archivo")

            val contador = ContadorBytes(flujoEntrada)
            val charset = if (b.latin1) Charsets.ISO_8859_1 else Charsets.UTF_8

            BufferedReader(
                InputStreamReader(BufferedInputStream(contador, 1 shl 20), charset),
                1 shl 20
            ).use { lector ->
                BufferedWriter(OutputStreamWriter(temp.outputStream(), Charsets.UTF_8), 1 shl 20).use { escritor ->
                    var ultimoAviso = System.nanoTime()

                    while (true) {
                        val linea = lector.readLine() ?: break
                        lineas++

                        val base = if (b.mayus) linea else linea.lowercase()
                        val encontrada =
                            if (b.todas) b.palabras.all { base.contains(it) }
                            else b.palabras.any { base.contains(it) }

                        if (encontrada && b.excluidas.none { base.contains(it) }) {
                            escritor.write(linea)
                            escritor.newLine()
                            coinc++
                            if (previa.size < PREVIA_MAX) {
                                previa.add(
                                    if (linea.length > PREVIA_ANCHO) linea.take(PREVIA_ANCHO) + "…" else linea
                                )
                            }
                        }

                        if ((lineas and 0xFFFL) == 0L) {
                            if (cancelar) {
                                cancelado = true
                                break
                            }
                            val ahora = System.nanoTime()
                            if (ahora - ultimoAviso > 250_000_000L) {
                                ultimoAviso = ahora
                                val c = coinc
                                val pct = if (total > 0)
                                    (contador.total * 1000 / total).toInt().coerceIn(0, 1000)
                                else 0
                                val texto = if (total > 0)
                                    "Procesando… ${"%.1f".format(pct / 10.0)} %  |  coincidencias: ${nf.format(c)}"
                                else
                                    "Procesando…  |  coincidencias: ${nf.format(c)}"
                                runOnUiThread {
                                    barra.progress = pct
                                    tvEstado.text = texto
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            error = "Hay una línea demasiado larga para procesar (el archivo casi no tiene saltos de línea)."
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }

        val segundos = (System.nanoTime() - inicio) / 1e9

        runOnUiThread {
            trabajando = false
            pantallaOcupada(false)

            if (error != null) {
                temp.delete()
                tvEstado.text = "Error: $error"
            } else if (coinc == 0L) {
                temp.delete()
                tvEstado.text = "No se encontró ninguna coincidencia (${nf.format(lineas)} líneas revisadas)."
            } else {
                resultadoTemp = temp
                resultadoCoinc = coinc
                if (!cancelado) barra.progress = 1000
                val titulo = if (cancelado) "Búsqueda cancelada. Se conservan los resultados parciales."
                else "Búsqueda terminada."
                tvEstado.text = "$titulo\n" +
                    "Tiempo: ${"%.1f".format(segundos)} s\n" +
                    "Líneas revisadas: ${nf.format(lineas)}\n" +
                    "Coincidencias: ${nf.format(coinc)}\n" +
                    "Revisá la vista previa y tocá \"Guardar .txt\"."
                mostrarPrevia(previa, b)
            }
        }
    }

    // ---------------------------------------------------------------- vista previa

    private fun mostrarPrevia(lineas: List<String>, b: Busqueda) {
        val nf = NumberFormat.getInstance()
        val total = resultadoCoinc
        tvPreviaTitulo.text =
            if (total > lineas.size)
                "Vista previa: primeras ${nf.format(lineas.size)} de ${nf.format(total)} coincidencias"
            else
                "Vista previa: ${nf.format(total)} coincidencias"
        tvPrevia.text = armarPrevia(lineas, b.palabras, b.mayus)
        llAcciones.visibility = View.VISIBLE
        btnCopiar.visibility = View.VISIBLE
        tvPreviaTitulo.visibility = View.VISIBLE
        tvPrevia.visibility = View.VISIBLE
    }

    /** Arma el texto de la vista previa resaltando en amarillo las palabras buscadas. */
    private fun armarPrevia(lineas: List<String>, palabras: List<String>, mayus: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        for (l in lineas) {
            val inicio = sb.length
            sb.append(l).append('\n')
            val base = if (mayus) l else l.lowercase()
            if (base.length != l.length) continue
            for (p in palabras) {
                var i = base.indexOf(p)
                while (i >= 0) {
                    sb.setSpan(
                        BackgroundColorSpan(0xFFFFEB3B.toInt()),
                        inicio + i,
                        inicio + i + p.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    i = base.indexOf(p, i + p.length)
                }
            }
        }
        return sb
    }

    /** Copia al portapapeles las líneas que se ven en la vista previa. */
    private fun copiarPrevia() {
        val texto = tvPrevia.text.toString().trimEnd()
        if (texto.isEmpty()) {
            aviso("No hay vista previa para copiar")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Vista previa", texto))
        aviso("Copiadas ${texto.lines().size} líneas de la vista previa")
    }

    private fun descartarResultado() {
        resultadoTemp?.delete()
        resultadoTemp = null
        resultadoCoinc = 0L
        tvPrevia.text = ""
        tvPrevia.visibility = View.GONE
        tvPreviaTitulo.visibility = View.GONE
        llAcciones.visibility = View.GONE
        btnCopiar.visibility = View.GONE
    }

    // ---------------------------------------------------------------- guardar

    private fun alPulsarGuardar() {
        if (trabajando) return
        if (resultadoTemp == null) {
            aviso("No hay resultados para guardar")
            return
        }
        // El usuario elige dónde guardar el .txt con los resultados.
        val base = archivoNombre.substringBeforeLast('.', archivoNombre)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "resultados_$base.txt")
        startActivityForResult(intent, REQ_GUARDAR)
    }

    private fun guardar(salida: Uri) {
        val temp = resultadoTemp ?: return
        val coincidencias = resultadoCoinc
        trabajando = true
        cancelar = false
        pantallaOcupada(true, false, "Guardando…")

        thread(name = "guardador") {
            val nf = NumberFormat.getInstance()
            val total = temp.length()
            var error: String? = null
            var cancelado = false

            try {
                val flujoSalida = contentResolver.openOutputStream(salida, "w")
                    ?: throw IOException("No se pudo crear el archivo de resultados")

                temp.inputStream().buffered(1 shl 20).use { origen ->
                    flujoSalida.buffered(1 shl 20).use { destino ->
                        val buffer = ByteArray(1 shl 16)
                        var copiado = 0L
                        var ultimoAviso = System.nanoTime()

                        while (true) {
                            val n = origen.read(buffer)
                            if (n < 0) break
                            destino.write(buffer, 0, n)
                            copiado += n

                            if (cancelar) {
                                cancelado = true
                                break
                            }
                            val ahora = System.nanoTime()
                            if (ahora - ultimoAviso > 250_000_000L) {
                                ultimoAviso = ahora
                                val pct = if (total > 0)
                                    (copiado * 1000 / total).toInt().coerceIn(0, 1000)
                                else 0
                                runOnUiThread {
                                    barra.progress = pct
                                    tvEstado.text = "Guardando… ${"%.1f".format(pct / 10.0)} %"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }

            // Si falló o se canceló, no dejamos un archivo incompleto.
            if (error != null || cancelado) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, salida)
                } catch (e: Exception) {
                    // Si no se puede borrar, queda el archivo parcial; no es grave.
                }
            }

            val nombre = if (error == null && !cancelado) nombreDe(salida) else ""

            runOnUiThread {
                trabajando = false
                pantallaOcupada(false)

                if (error != null) {
                    tvEstado.text = "Error al guardar: $error"
                } else if (cancelado) {
                    tvEstado.text = "Guardado cancelado. Podés intentarlo de nuevo."
                } else {
                    barra.progress = 1000
                    descartarResultado()
                    tvEstado.text = "Listo. Se guardaron ${nf.format(coincidencias)} coincidencias.\n" +
                        "Archivo: $nombre"
                }
            }
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun pantallaOcupada(ocupado: Boolean, indeterminada: Boolean = false, texto: String = "") {
        btnElegir.isEnabled = !ocupado
        btnBuscar.isEnabled = !ocupado
        btnGuardar.isEnabled = !ocupado
        btnDescartar.isEnabled = !ocupado
        btnCopiar.isEnabled = !ocupado
        etBuscar.isEnabled = !ocupado
        etExcluir.isEnabled = !ocupado
        cbMayus.isEnabled = !ocupado
        cbLatin1.isEnabled = !ocupado
        findViewById<View>(R.id.rbCualquiera).isEnabled = !ocupado
        rbTodas.isEnabled = !ocupado

        btnCancelar.visibility = if (ocupado) View.VISIBLE else View.GONE
        btnCancelar.isEnabled = true

        barra.visibility = if (ocupado || barra.progress > 0) View.VISIBLE else View.INVISIBLE
        barra.isIndeterminate = ocupado && indeterminada
        if (ocupado) {
            barra.progress = 0
            tvEstado.text = texto
            // Evita que la pantalla se apague y se corte la operación.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            barra.isIndeterminate = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Borra temporales que hayan quedado de una sesión anterior. */
    private fun limpiarTemporales() {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("resultados")) f.delete()
            }
        } catch (e: Exception) {
            // no es grave
        }
    }

    private fun nombreDe(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i) ?: uri.lastPathSegment.orEmpty()
                }
            }
        } catch (e: Exception) {
            // se usa el nombre por defecto
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun formatoTamano(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
    }

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()
}

/** Cuenta los bytes leídos para poder mostrar el avance en porcentaje. */
private class ContadorBytes(entrada: InputStream) : FilterInputStream(entrada) {
    @Volatile var total = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) total++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) total += n
        return n
    }
}
