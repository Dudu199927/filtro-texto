package com.filtrotexto.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var cbSeparar: CheckBox
    private lateinit var btnHistorial: Button
    private lateinit var btnRecientes: Button
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
        cbSeparar = findViewById(R.id.cbSeparar)
        btnHistorial = findViewById(R.id.btnHistorial)
        btnHistorial.setOnClickListener { mostrarHistorial() }
        btnRecientes = findViewById(R.id.btnRecientes)
        btnRecientes.setOnClickListener { mostrarRecientes() }
        cbSeparar.setOnCheckedChangeListener { _, _ ->
            if (Estado.previa.isNotEmpty()) mostrarPrevia()
        }
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
        etBuscar.isEnabled = !ocupado
        etExcluir.isEnabled = !ocupado
        cbMayus.isEnabled = !ocupado
        cbLatin1.isEnabled = !ocupado
        btnHistorial.isEnabled = !ocupado
        btnRecientes.isEnabled = !ocupado
        rbCualquiera.isEnabled = !ocupado
        rbTodas.isEnabled = !ocupado

        btnCancelar.visibility = if (ocupado) View.VISIBLE else View.GONE
        btnCancelar.isEnabled = !Estado.cancelar

        barra.visibility = if (ocupado || Estado.progreso > 0) View.VISIBLE else View.INVISIBLE
        barra.isIndeterminate = ocupado && Estado.indeterminada
        barra.progress = Estado.progreso
        tvEstado.text = Estado.mensaje

        val hayResultado = Estado.resultadoTemp != null
        // La vista previa se ve también mientras se busca ("en vivo"); Guardar solo al terminar.
        val hayPrevia = hayResultado || (ocupado && Estado.previa.isNotEmpty())
        llAcciones.visibility = if (hayResultado) View.VISIBLE else View.GONE
        val visibilidad = if (hayPrevia) View.VISIBLE else View.GONE
        btnCopiar.visibility = visibilidad
        tvPreviaTitulo.visibility = visibilidad
        tvPrevia.visibility = visibilidad
        cbSeparar.visibility = visibilidad

        if (hayPrevia) {
            // Si el usuario está seleccionando texto, no se repinta para no cortarle la selección.
            val seleccionando = ocupado && tvPrevia.hasSelection()
            if (previaPintada != Estado.previaVersion && !seleccionando) {
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
        var persistente = false
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            persistente = true
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

        // Solo se recuerdan los archivos que Android deja volver a abrir más tarde.
        if (persistente) {
            for (fuera in ArchivosRecientes.guardar(this, ArchivoReciente(uri.toString(), nombre, tam))) {
                soltarPermiso(fuera.uri)
            }
        }
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

        Historial.guardar(
            this,
            Busqueda(
                etBuscar.text.toString().trim(), etExcluir.text.toString().trim(),
                rbTodas.isChecked, mayus, cbLatin1.isChecked
            )
        )

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

    /** Lista los últimos archivos abiertos; al tocar uno se carga (queda listo para buscar). */
    private fun mostrarRecientes() {
        if (Estado.trabajando) return
        val lista = ArchivosRecientes.leer(this)
        if (lista.isEmpty()) {
            aviso("Todavía no hay archivos recientes")
            return
        }
        val opciones = lista.map {
            if (it.tam >= 0) it.nombre + "\n" + formatoTamano(it.tam) else it.nombre
        } + "Borrar la lista"
        AlertDialog.Builder(this)
            .setTitle("Archivos recientes")
            .setItems(opciones.toTypedArray()) { _, cual ->
                if (cual >= lista.size) {
                    for (a in lista) soltarPermiso(a.uri)
                    ArchivosRecientes.borrar(this)
                    aviso("Lista borrada")
                } else {
                    abrirReciente(lista[cual])
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun abrirReciente(a: ArchivoReciente) {
        val uri = Uri.parse(a.uri)
        val disponible = try {
            contentResolver.openInputStream(uri)?.close()
            true
        } catch (e: Exception) {
            false
        }
        if (!disponible) {
            soltarPermiso(a.uri)
            ArchivosRecientes.quitar(this, a.uri)
            aviso("Ese archivo ya no está disponible. Elegilo de nuevo con el botón 1")
            return
        }
        cargarArchivo(uri)
    }

    private fun soltarPermiso(uri: String) {
        try {
            if (Estado.archivoUri?.toString() == uri) return
            contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // no es grave
        }
    }

    /** Lista las últimas búsquedas; al tocar una se completan los campos y se busca enseguida. */
    private fun mostrarHistorial() {
        if (Estado.trabajando) return
        val lista = Historial.leer(this)
        if (lista.isEmpty()) {
            aviso("Todavía no hay búsquedas guardadas")
            return
        }
        val opciones = lista.map { it.titulo() } + "Borrar el historial"
        AlertDialog.Builder(this)
            .setTitle("Búsquedas recientes")
            .setItems(opciones.toTypedArray()) { _, cual ->
                if (cual >= lista.size) {
                    Historial.borrar(this)
                    aviso("Historial borrado")
                } else {
                    usarBusqueda(lista[cual])
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun usarBusqueda(b: Busqueda) {
        etBuscar.setText(b.palabras)
        etExcluir.setText(b.excluir)
        rbTodas.isChecked = b.todas
        rbCualquiera.isChecked = !b.todas
        cbMayus.isChecked = b.mayus
        cbLatin1.isChecked = b.latin1
        if (Estado.archivoUri == null) {
            aviso("Búsqueda cargada. Elegí un archivo con el botón 1 y tocá Buscar")
        } else {
            alPulsarBuscar()
        }
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

    /** Un «:» que no es el de «://» de las direcciones web. */
    private val SEPARADOR = Regex(":(?!//)")

    private fun mostrarPrevia() {
        val nf = NumberFormat.getInstance()
        val enVivo = Estado.trabajando
        val total = if (enVivo) Estado.coincVivas else Estado.resultadoCoinc
        val n = Estado.previa.size
        val prefijo = if (enVivo) "Vista previa en vivo (sigue buscando)" else "Vista previa"
        tvPreviaTitulo.text =
            if (total > n)
                "$prefijo: primeras ${nf.format(n)} de ${nf.format(total)} coincidencias"
            else
                "$prefijo: ${nf.format(total)} coincidencias"
        // Con "separar", cada «:» se rodea de espacios: así, al tocar un campo, se selecciona solo ese
        // campo y no la línea entera. El archivo que se guarda no cambia.
        val lineas = if (cbSeparar.isChecked) Estado.previa.map { SEPARADOR.replace(it, "  :  ") } else Estado.previa
        tvPrevia.text = armarPrevia(lineas, Estado.previaPalabras, Estado.previaMayus)
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
        val texto = Estado.previa.joinToString("\n").trimEnd()
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
