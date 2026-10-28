package com.filtrotexto.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat

/**
 * Pantalla del Filtro de Texto.
 *
 * El trabajo pesado (buscar y guardar) lo hace [TrabajoService] en primer plano;
 * esta pantalla solo muestra el [Estado] y puede cerrarse o reabrirse sin
 * interrumpir la búsqueda.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_ABRIR = 1
        const val REQ_GUARDAR = 2
        const val REQ_NOTIFICACIONES = 3
    }

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
    private lateinit var rbCualquiera: RadioButton
    private lateinit var rbTodas: RadioButton
    private lateinit var cbMayus: CheckBox
    private lateinit var cbLatin1: CheckBox
    private lateinit var barra: ProgressBar

    /** Versión de la vista previa que ya está dibujada (para no rehacerla a cada avance). */
    private var previaPintada = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Estado.temporalesLimpiados) {
            limpiarTemporales()
            Estado.temporalesLimpiados = true
        }

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
        rbCualquiera = findViewById(R.id.rbCualquiera)
        rbTodas = findViewById(R.id.rbTodas)
        cbMayus = findViewById(R.id.cbMayus)
        cbLatin1 = findViewById(R.id.cbLatin1)
        barra = findViewById(R.id.barra)

        btnElegir.setOnClickListener { elegirArchivo() }
        btnBuscar.setOnClickListener { alPulsarBuscar() }
        btnGuardar.setOnClickListener { alPulsarGuardar() }
        btnCopiar.setOnClickListener { copiarPrevia() }
        btnDescartar.setOnClickListener {
            Estado.limpiarResultado()
            Estado.progreso = 0
            Estado.mensaje = "Resultados descartados."
            pintar()
        }
        btnCancelar.setOnClickListener {
            Estado.cancelar = true
            Estado.mensaje = "Cancelando…"
            pintar()
        }

        manejarIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Estado.oyente = { pintar() }
        pintar()
    }

    override fun onStop() {
        super.onStop()
        Estado.oyente = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        manejarIntent(intent)
    }

    // ---------------------------------------------------------------- pantalla

    /** Dibuja en pantalla lo que dice el [Estado]. */
    private fun pintar() {
        val ocupado = Estado.trabajando

        tvArchivo.text = if (Estado.archivoUri == null) {
            "Ningún archivo seleccionado"
        } else {
            val tam = if (Estado.archivoTam >= 0) " (${formatoTamano(Estado.archivoTam)})" else ""
            "Archivo cargado: ${Estado.archivoNombre}$tam"
        }

        btnElegir.isEnabled = !ocupado
        btnBuscar.isEnabled = !ocupado
        btnGuardar.isEnabled = !ocupado
        btnDescartar.isEnabled = !ocupado
        btnCopiar.isEnabled = !ocupado
        etBuscar.isEnabled = !ocupado
        etExcluir.isEnabled = !ocupado
        cbMayus.isEnabled = !ocupado
        cbLatin1.isEnabled = !ocupado
        rbCualquiera.isEnabled = !ocupado
        rbTodas.isEnabled = !ocupado

        btnCancelar.visibility = if (ocupado) View.VISIBLE else View.GONE
        btnCancelar.isEnabled = !Estado.cancelar

        barra.visibility = if (ocupado || Estado.progreso > 0) View.VISIBLE else View.INVISIBLE
        barra.isIndeterminate = ocupado && Estado.indeterminada
        barra.progress = Estado.progreso
        tvEstado.text = Estado.mensaje

        val hayResultado = Estado.resultadoTemp != null
        val visibilidad = if (hayResultado) View.VISIBLE else View.GONE
        llAcciones.visibility = visibilidad
        btnCopiar.visibility = visibilidad
        tvPreviaTitulo.visibility = visibilidad
        tvPrevia.visibility = visibilidad

        if (hayResultado) {
            if (previaPintada != Estado.previaVersion) {
                mostrarPrevia()
                previaPintada = Estado.previaVersion
            }
        } else {
            previaPintada = -1
            tvPrevia.text = ""
        }
    }

    // ---------------------------------------------------------------- elegir / recibir archivo

    private fun elegirArchivo() {
        if (Estado.trabajando) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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
        if (Estado.trabajando) {
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
        Estado.limpiarResultado()

        // Permiso persistente: sigue valiendo aunque la app se cierre en segundo plano.
        // (Los archivos recibidos desde "Compartir" no lo permiten; en ese caso se ignora.)
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val anterior = Estado.archivoUri
            if (anterior != null && anterior != uri) {
                try {
                    contentResolver.releasePersistableUriPermission(anterior, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // no es grave
                }
            }
        } catch (e: Exception) {
            // no es grave
        }

        var nombre = uri.lastPathSegment ?: "archivo"
        var tam = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iNombre = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iTam = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iNombre >= 0 && !c.isNull(iNombre)) nombre = c.getString(iNombre)
                    if (iTam >= 0 && !c.isNull(iTam)) tam = c.getLong(iTam)
                }
            }
        } catch (e: Exception) {
            // Si no se puede leer el nombre o el tamaño, se sigue igual.
        }

        Estado.archivoUri = uri
        Estado.archivoNombre = nombre
        Estado.archivoTam = tam
        Estado.progreso = 0
        Estado.mensaje = ""
        pintar()
    }

    @Deprecated("Se mantiene para no depender de librerías externas")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_ABRIR -> cargarArchivo(uri)
            REQ_GUARDAR -> iniciarGuardado(uri)
        }
    }

    // ---------------------------------------------------------------- buscar

    private fun alPulsarBuscar() {
        if (Estado.trabajando) return
        val entrada = Estado.archivoUri
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
        val excluidas = separar(etExcluir.text.toString(), mayus)

        Estado.limpiarResultado()
        Estado.cancelar = false
        Estado.trabajando = true
        Estado.progreso = 0
        Estado.indeterminada = Estado.archivoTam <= 0
        Estado.mensaje = "Procesando…"

        val i = Intent(this, TrabajoService::class.java)
            .setAction(TrabajoService.ACCION_BUSCAR)
            .putExtra(TrabajoService.EXTRA_ENTRADA, entrada.toString())
            .putExtra(TrabajoService.EXTRA_PALABRAS, palabras.toTypedArray())
            .putExtra(TrabajoService.EXTRA_EXCLUIDAS, excluidas.toTypedArray())
            .putExtra(TrabajoService.EXTRA_TODAS, rbTodas.isChecked)
            .putExtra(TrabajoService.EXTRA_MAYUS, mayus)
            .putExtra(TrabajoService.EXTRA_LATIN1, cbLatin1.isChecked)
            .putExtra(TrabajoService.EXTRA_TOTAL, Estado.archivoTam)

        if (arrancarServicio(i)) {
            pedirPermisoNotificaciones()
        }
        pintar()
    }

    private fun separar(texto: String, mayus: Boolean): List<String> =
        texto.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (mayus) it else it.lowercase() }

    /** Inicia el servicio en primer plano. Si no se puede, deja un mensaje y devuelve false. */
    private fun arrancarServicio(i: Intent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            true
        } catch (e: Exception) {
            Estado.trabajando = false
            Estado.mensaje = "No se pudo iniciar el trabajo en segundo plano: ${e.message}"
            false
        }
    }

    /** En Android 13 o más hay que pedir permiso para mostrar la notificación de progreso. */
    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICACIONES)
        }
    }

    // ---------------------------------------------------------------- vista previa

    private fun mostrarPrevia() {
        val nf = NumberFormat.getInstance()
        val total = Estado.resultadoCoinc
        val n = Estado.previa.size
        tvPreviaTitulo.text =
            if (total > n)
                "Vista previa: primeras ${nf.format(n)} de ${nf.format(total)} coincidencias"
            else
                "Vista previa: ${nf.format(total)} coincidencias"
        tvPrevia.text = armarPrevia(Estado.previa, Estado.previaPalabras, Estado.previaMayus)
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

    // ---------------------------------------------------------------- guardar

    private fun alPulsarGuardar() {
        if (Estado.trabajando) return
        if (Estado.resultadoTemp == null) {
            aviso("No hay resultados para guardar")
            return
        }
        // El usuario elige dónde guardar el .txt con los resultados.
        val base = Estado.archivoNombre.substringBeforeLast('.', Estado.archivoNombre)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "resultados_$base.txt")
        startActivityForResult(intent, REQ_GUARDAR)
    }

    private fun iniciarGuardado(salida: Uri) {
        val temp = Estado.resultadoTemp ?: return

        Estado.cancelar = false
        Estado.trabajando = true
        Estado.progreso = 0
        Estado.indeterminada = false
        Estado.mensaje = "Guardando…"

        val i = Intent(this, TrabajoService::class.java)
            .setAction(TrabajoService.ACCION_GUARDAR)
            .putExtra(TrabajoService.EXTRA_SALIDA, salida.toString())
            .putExtra(TrabajoService.EXTRA_TEMP, temp.absolutePath)
            .putExtra(TrabajoService.EXTRA_COINC, Estado.resultadoCoinc)

        arrancarServicio(i)
        pintar()
    }

    // ---------------------------------------------------------------- utilidades

    /** Borra temporales que hayan quedado de una sesión anterior. */
    private fun limpiarTemporales() {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("resultados") || f.name.startsWith("rar")) f.delete()
            }
        } catch (e: Exception) {
            // no es grave
        }
    }

    private fun formatoTamano(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
    }

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()
}
