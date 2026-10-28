package com.filtrotexto.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.text.NumberFormat
import kotlin.concurrent.thread

/**
 * Hace la búsqueda y el guardado como servicio en primer plano: Android no lo
 * duerme ni lo cierra aunque la pantalla se apague o la app quede en segundo
 * plano. Muestra una notificación con el progreso y un botón para cancelar.
 */
class TrabajoService : Service() {

    companion object {
        const val ACCION_BUSCAR = "com.filtrotexto.app.BUSCAR"
        const val ACCION_GUARDAR = "com.filtrotexto.app.GUARDAR"
        const val ACCION_CANCELAR = "com.filtrotexto.app.CANCELAR"

        const val EXTRA_ENTRADA = "entrada"
        const val EXTRA_PALABRAS = "palabras"
        const val EXTRA_EXCLUIDAS = "excluidas"
        const val EXTRA_TODAS = "todas"
        const val EXTRA_MAYUS = "mayus"
        const val EXTRA_LATIN1 = "latin1"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_SALIDA = "salida"
        const val EXTRA_TEMP = "temp"
        const val EXTRA_COINC = "coinc"

        private const val CANAL_PROGRESO = "progreso"
        private const val CANAL_FIN = "fin"
        private const val ID_PROGRESO = 1
        private const val ID_FIN = 2
    }

    @Volatile private var trabajoActivo = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var ultimaNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accion = intent?.action

        if (accion == ACCION_CANCELAR) {
            Estado.cancelar = true
            return START_NOT_STICKY
        }

        // Si arrancó con startForegroundService hay que pasar a primer plano enseguida.
        ponerEnPrimerPlano(if (accion == ACCION_GUARDAR) "Guardando…" else "Buscando…", 0, true)

        if (intent == null || trabajoActivo || (accion != ACCION_BUSCAR && accion != ACCION_GUARDAR)) {
            if (!trabajoActivo) stopSelf()
            return START_NOT_STICKY
        }

        trabajoActivo = true
        tomarWakeLock()

        thread(name = "trabajo") {
            try {
                if (accion == ACCION_BUSCAR) buscar(intent) else guardar(intent)
            } catch (e: Throwable) {
                val msg = "Error: ${e.message ?: e.javaClass.simpleName}"
                Estado.actualizar {
                    Estado.trabajando = false
                    Estado.mensaje = msg
                }
            } finally {
                liberarWakeLock()
                trabajoActivo = false
                Estado.actualizar { Estado.trabajando = false }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        liberarWakeLock()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- búsqueda

    private fun buscar(intent: Intent) {
        val textoUri = intent.getStringExtra(EXTRA_ENTRADA)
        if (textoUri == null) {
            terminarConError("No se recibió el archivo a procesar")
            return
        }
        val entrada = Uri.parse(textoUri)
        val palabras = intent.getStringArrayExtra(EXTRA_PALABRAS)?.toList() ?: emptyList()
        val excluidas = intent.getStringArrayExtra(EXTRA_EXCLUIDAS)?.toList() ?: emptyList()
        val todas = intent.getBooleanExtra(EXTRA_TODAS, false)
        val mayus = intent.getBooleanExtra(EXTRA_MAYUS, false)
        val latin1 = intent.getBooleanExtra(EXTRA_LATIN1, false)
        val total = intent.getLongExtra(EXTRA_TOTAL, -1L)
        val nombre = nombreDe(entrada)

        val temp: File
        try {
            temp = File.createTempFile("resultados", ".tmp", cacheDir)
        } catch (e: IOException) {
            terminarConError("No se pudo crear un archivo temporal: ${e.message}")
            return
        }

        val inicio = System.nanoTime()
        val nf = NumberFormat.getInstance()
        var buscador: Buscador? = null
        var error: String? = null

        try {
            val flujoEntrada = contentResolver.openInputStream(entrada)
                ?: throw IOException("No se pudo abrir el archivo")
            val contador = ContadorDeBytes(flujoEntrada)
            val parametros = Parametros(palabras, excluidas, todas, mayus, latin1)

            BufferedWriter(OutputStreamWriter(temp.outputStream(), Charsets.UTF_8), 1 shl 20).use { escritor ->
                val b = Buscador(
                    p = parametros,
                    escritor = escritor,
                    bytesLeidos = { contador.total },
                    total = total,
                    cancelado = { Estado.cancelar },
                    abrirRar = Rar.abridor(this),
                    carpetaTemp = cacheDir,
                    alAvanzar = { pct, texto ->
                        Estado.actualizar {
                            Estado.progreso = pct
                            Estado.mensaje = texto
                        }
                        actualizarNotificacion(texto, pct, total <= 0)
                    }
                )
                buscador = b
                BufferedInputStream(contador, 1 shl 20).use { b.procesar(it, nombre) }
            }
        } catch (e: OutOfMemoryError) {
            error = "Hay una línea o un archivo demasiado grande para procesar."
        } catch (e: SecurityException) {
            error = "Android ya no permite leer este archivo. Elegilo de nuevo con el botón 1."
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }

        val segundos = (System.nanoTime() - inicio) / 1e9
        val b = buscador
        val totalLineas = b?.lineas ?: 0L
        val totalCoinc = b?.coincidencias ?: 0L
        val cancelado = b?.interrumpido ?: false
        val detalleArchivos = if (b != null && b.enArchivo) detalleDeArchivos(b, nf) else ""

        when {
            error != null -> {
                temp.delete()
                val msg = "Error: $error"
                Estado.actualizar {
                    Estado.trabajando = false
                    Estado.progreso = 0
                    Estado.mensaje = msg
                }
                avisarFin("La búsqueda terminó con un error")
            }

            totalCoinc == 0L -> {
                temp.delete()
                val msg = "No se encontró ninguna coincidencia (${nf.format(totalLineas)} líneas revisadas)." +
                    (if (detalleArchivos.isEmpty()) "" else "\n$detalleArchivos")
                Estado.actualizar {
                    Estado.trabajando = false
                    Estado.progreso = 0
                    Estado.mensaje = msg
                }
                avisarFin("Sin coincidencias")
            }

            else -> {
                val titulo = if (cancelado) "Búsqueda cancelada. Se conservan los resultados parciales."
                else "Búsqueda terminada."
                val msg = "$titulo\n" +
                    "Tiempo: ${"%.1f".format(segundos)} s\n" +
                    "Líneas revisadas: ${nf.format(totalLineas)}\n" +
                    "Coincidencias: ${nf.format(totalCoinc)}\n" +
                    (if (detalleArchivos.isEmpty()) "" else "$detalleArchivos\n") +
                    "Revisá la vista previa y tocá \"Guardar .txt\"."
                val previaFinal: List<String> = b?.previa ?: emptyList()
                Estado.actualizar {
                    Estado.limpiarResultado()
                    Estado.resultadoTemp = temp
                    Estado.resultadoCoinc = totalCoinc
                    Estado.previa = previaFinal
                    Estado.previaPalabras = palabras
                    Estado.previaMayus = mayus
                    Estado.previaVersion++
                    if (!cancelado) Estado.progreso = 1000
                    Estado.mensaje = msg
                    Estado.trabajando = false
                }
                avisarFin(
                    if (cancelado) "Búsqueda cancelada: ${nf.format(totalCoinc)} coincidencias"
                    else "Búsqueda terminada: ${nf.format(totalCoinc)} coincidencias"
                )
            }
        }
    }

    /** Resumen de los archivos revisados dentro de un ZIP o DOCX. */
    private fun detalleDeArchivos(b: Buscador, nf: NumberFormat): String {
        val sb = StringBuilder("Archivos revisados: ${nf.format(b.archivosLeidos)}")
        if (b.cantOmitidos > 0) {
            val nombres = b.omitidos.take(5).joinToString(", ")
            val resto = b.cantOmitidos - minOf(b.omitidos.size, 5)
            sb.append("\nSin leer (no son de texto ni DOCX): ${nf.format(b.cantOmitidos)}")
            sb.append(" → ").append(nombres)
            if (resto > 0) sb.append(" y ${nf.format(resto)} más")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- guardado

    private fun guardar(intent: Intent) {
        val textoSalida = intent.getStringExtra(EXTRA_SALIDA)
        val rutaTemp = intent.getStringExtra(EXTRA_TEMP)
        if (textoSalida == null || rutaTemp == null) {
            terminarConError("Faltan datos para guardar el archivo")
            return
        }
        val salida = Uri.parse(textoSalida)
        val temp = File(rutaTemp)
        val coincidencias = intent.getLongExtra(EXTRA_COINC, 0L)
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

                        if (Estado.cancelar) {
                            cancelado = true
                            break
                        }
                        val ahora = System.nanoTime()
                        if (ahora - ultimoAviso > 250_000_000L) {
                            ultimoAviso = ahora
                            val pct = if (total > 0)
                                (copiado * 1000 / total).toInt().coerceIn(0, 1000)
                            else 0
                            val texto = "Guardando… ${"%.1f".format(pct / 10.0)} %"
                            Estado.actualizar {
                                Estado.progreso = pct
                                Estado.mensaje = texto
                            }
                            actualizarNotificacion(texto, pct, false)
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

        when {
            error != null -> {
                val msg = "Error al guardar: $error"
                Estado.actualizar {
                    Estado.trabajando = false
                    Estado.mensaje = msg
                }
                avisarFin("No se pudo guardar el archivo")
            }

            cancelado -> {
                Estado.actualizar {
                    Estado.trabajando = false
                    Estado.mensaje = "Guardado cancelado. Podés intentarlo de nuevo."
                }
                avisarFin("Guardado cancelado")
            }

            else -> {
                val msg = "Listo. Se guardaron ${nf.format(coincidencias)} coincidencias.\nArchivo: $nombre"
                Estado.actualizar {
                    Estado.limpiarResultado()
                    Estado.progreso = 1000
                    Estado.mensaje = msg
                    Estado.trabajando = false
                }
                avisarFin("Archivo guardado: $nombre")
            }
        }
    }

    private fun terminarConError(texto: String) {
        Estado.actualizar {
            Estado.trabajando = false
            Estado.progreso = 0
            Estado.mensaje = "Error: $texto"
        }
        avisarFin("Error: $texto")
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

    // ---------------------------------------------------------------- notificaciones

    private fun nuevoConstructor(canal: String, nombre: String, importancia: Int): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(canal) == null) {
                nm.createNotificationChannel(NotificationChannel(canal, nombre, importancia))
            }
            Notification.Builder(this, canal)
        } else {
            Notification.Builder(this)
        }
    }

    private fun abrirApp(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun construirNotificacion(texto: String, pct: Int, indeterminada: Boolean): Notification {
        val cancelar = PendingIntent.getService(
            this, 1,
            Intent(this, TrabajoService::class.java).setAction(ACCION_CANCELAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return nuevoConstructor(CANAL_PROGRESO, "Búsqueda en curso", NotificationManager.IMPORTANCE_LOW)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Filtro de Texto")
            .setContentText(texto)
            .setContentIntent(abrirApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, pct, indeterminada)
            .build()
    }

    private fun ponerEnPrimerPlano(texto: String, pct: Int, indeterminada: Boolean) {
        val notificacion = construirNotificacion(texto, pct, indeterminada)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESO, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESO, notificacion)
        }
    }

    /** Actualiza la notificación como máximo una vez por segundo. */
    private fun actualizarNotificacion(texto: String, pct: Int, indeterminada: Boolean) {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimaNotif < 1000) return
        ultimaNotif = ahora
        try {
            getSystemService(NotificationManager::class.java)
                .notify(ID_PROGRESO, construirNotificacion(texto, pct, indeterminada))
        } catch (e: Exception) {
            // si no se puede actualizar, el trabajo sigue igual
        }
    }

    /** Aviso final, que queda en el panel de notificaciones hasta que lo toques. */
    private fun avisarFin(texto: String) {
        try {
            val notificacion = nuevoConstructor(CANAL_FIN, "Trabajo terminado", NotificationManager.IMPORTANCE_DEFAULT)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Filtro de Texto")
                .setContentText(texto)
                .setContentIntent(abrirApp())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(ID_FIN, notificacion)
        } catch (e: Exception) {
            // sin permiso de notificaciones, simplemente no se muestra
        }
    }

    // ---------------------------------------------------------------- batería

    private fun tomarWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "filtrotexto:trabajo")
            lock.setReferenceCounted(false)
            lock.acquire(6 * 60 * 60 * 1000L)
            wakeLock = lock
        } catch (e: Exception) {
            // si no se consigue, el servicio en primer plano igual protege el trabajo
        }
    }

    private fun liberarWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // no es grave
        }
        wakeLock = null
    }
}

/** Cuenta los bytes leídos para poder mostrar el avance en porcentaje. */
private class ContadorDeBytes(entrada: InputStream) : FilterInputStream(entrada) {
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
