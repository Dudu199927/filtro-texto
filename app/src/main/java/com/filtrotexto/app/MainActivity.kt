package com.filtrotexto.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.NumberFormat
import kotlin.concurrent.thread

/**
 * Filtro de texto para archivos grandes.
 * Lee el archivo línea por línea (streaming), por lo que casi no usa memoria
 * aunque el archivo pese varios GB.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_ABRIR = 1
        const val REQ_GUARDAR = 2
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
    private lateinit var tvArchivo: TextView
    private lateinit var tvEstado: TextView
    private lateinit var etBuscar: EditText
    private lateinit var etExcluir: EditText
    private lateinit var rbTodas: RadioButton
    private lateinit var cbMayus: CheckBox
    private lateinit var cbLatin1: CheckBox
    private lateinit var barra: ProgressBar

    private var archivoUri: Uri? = null
    private var archivoNombre = ""
    private var archivoTam = -1L
    private var pendiente: Busqueda? = null

    @Volatile private var cancelar = false
    @Volatile private var trabajando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnElegir = findViewById(R.id.btnElegir)
        btnBuscar = findViewById(R.id.btnBuscar)
        btnCancelar = findViewById(R.id.btnCancelar)
        tvArchivo = findViewById(R.id.tvArchivo)
        tvEstado = findViewById(R.id.tvEstado)
        etBuscar = findViewById(R.id.etBuscar)
        etExcluir = findViewById(R.id.etExcluir)
        rbTodas = findViewById(R.id.rbTodas)
        cbMayus = findViewById(R.id.cbMayus)
        cbLatin1 = findViewById(R.id.cbLatin1)
        barra = findViewById(R.id.barra)

        btnElegir.setOnClickListener { elegirArchivo() }
        btnBuscar.setOnClickListener { alPulsarBuscar() }
        btnCancelar.setOnClickListener {
            cancelar = true
            btnCancelar.isEnabled = false
            tvEstado.text = "Cancelando…"
        }
    }

    // ---------------------------------------------------------------- elegir archivo

    private fun elegirArchivo() {
        if (trabajando) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(intent, REQ_ABRIR)
    }

    @Deprecated("Se mantiene para no depender de librerías externas")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_ABRIR -> {
                archivoUri = uri
                archivoNombre = "archivo"
                archivoTam = -1L
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val iNombre = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val iTam = c.getColumnIndex(OpenableColumns.SIZE)
                            if (iNombre >= 0) archivoNombre = c.getString(iNombre) ?: "archivo"
                            if (iTam >= 0 && !c.isNull(iTam)) archivoTam = c.getLong(iTam)
                        }
                    }
                } catch (e: Exception) {
                    // Si no se puede leer el nombre o el tamaño, se sigue igual.
                }
                val tam = if (archivoTam >= 0) " (${formatoTamano(archivoTam)})" else ""
                tvArchivo.text = "Archivo cargado: $archivoNombre$tam"
                tvEstado.text = ""
                barra.visibility = View.INVISIBLE
            }

            REQ_GUARDAR -> {
                val b = pendiente ?: return
                val entrada = archivoUri ?: return
                iniciar(entrada, uri, b)
            }
        }
    }

    // ---------------------------------------------------------------- buscar

    private fun alPulsarBuscar() {
        if (trabajando) return
        if (archivoUri == null) {
            aviso("Primero elegí un archivo")
            return
        }
        val mayus = cbMayus.isChecked
        val palabras = separar(etBuscar.text.toString(), mayus)
        if (palabras.isEmpty()) {
            aviso("Escribí al menos una palabra a buscar")
            return
        }
        pendiente = Busqueda(
            palabras = palabras,
            todas = rbTodas.isChecked,
            mayus = mayus,
            excluidas = separar(etExcluir.text.toString(), mayus),
            latin1 = cbLatin1.isChecked
        )

        // El usuario elige dónde guardar el .txt con los resultados.
        val base = archivoNombre.substringBeforeLast('.', archivoNombre)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "resultados_$base.txt")
        startActivityForResult(intent, REQ_GUARDAR)
    }

    private fun separar(texto: String, mayus: Boolean): List<String> =
        texto.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (mayus) it else it.lowercase() }

    private fun iniciar(entrada: Uri, salida: Uri, b: Busqueda) {
        trabajando = true
        cancelar = false
        pantallaOcupada(true)
        val total = archivoTam
        thread(name = "buscador") { buscar(entrada, salida, b, total) }
    }

    private fun buscar(entrada: Uri, salida: Uri, b: Busqueda, total: Long) {
        val inicio = System.nanoTime()
        val nf = NumberFormat.getInstance()
        var lineas = 0L
        var coinc = 0L
        var error: String? = null
        var cancelado = false

        try {
            val flujoEntrada = contentResolver.openInputStream(entrada)
                ?: throw IOException("No se pudo abrir el archivo")
            val flujoSalida = contentResolver.openOutputStream(salida, "w")
                ?: throw IOException("No se pudo crear el archivo de resultados")

            val contador = ContadorBytes(flujoEntrada)
            val charset = if (b.latin1) Charsets.ISO_8859_1 else Charsets.UTF_8

            BufferedReader(
                InputStreamReader(BufferedInputStream(contador, 1 shl 20), charset),
                1 shl 20
            ).use { lector ->
                BufferedWriter(OutputStreamWriter(flujoSalida, Charsets.UTF_8), 1 shl 20).use { escritor ->
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

        // Sin coincidencias no dejamos un archivo vacío.
        if (coinc == 0L) {
            try {
                DocumentsContract.deleteDocument(contentResolver, salida)
            } catch (e: Exception) {
                // Si no se puede borrar, queda el archivo vacío; no es grave.
            }
        }

        val mensaje = when {
            error != null -> "Error: $error"
            coinc == 0L -> "No se encontró ninguna coincidencia (${nf.format(lineas)} líneas revisadas). " +
                "No se creó ningún archivo."
            else -> {
                val titulo = if (cancelado) "Cancelado. Se guardaron los resultados parciales." else "Listo."
                "$titulo\n" +
                    "Tiempo: ${"%.1f".format(segundos)} s\n" +
                    "Líneas revisadas: ${nf.format(lineas)}\n" +
                    "Coincidencias exportadas: ${nf.format(coinc)}\n" +
                    "Archivo: ${nombreDe(salida)}"
            }
        }

        runOnUiThread {
            trabajando = false
            pantallaOcupada(false)
            if (error == null && !cancelado) barra.progress = 1000
            tvEstado.text = mensaje
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun pantallaOcupada(ocupado: Boolean) {
        btnElegir.isEnabled = !ocupado
        btnBuscar.isEnabled = !ocupado
        etBuscar.isEnabled = !ocupado
        etExcluir.isEnabled = !ocupado
        cbMayus.isEnabled = !ocupado
        cbLatin1.isEnabled = !ocupado
        findViewById<View>(R.id.rbCualquiera).isEnabled = !ocupado
        rbTodas.isEnabled = !ocupado

        btnCancelar.visibility = if (ocupado) View.VISIBLE else View.GONE
        btnCancelar.isEnabled = true

        barra.visibility = if (ocupado || barra.progress > 0) View.VISIBLE else View.INVISIBLE
        barra.isIndeterminate = ocupado && archivoTam <= 0
        if (ocupado) {
            barra.progress = 0
            tvEstado.text = "Procesando…"
            // Evita que la pantalla se apague y se corte la búsqueda.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            barra.isIndeterminate = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
