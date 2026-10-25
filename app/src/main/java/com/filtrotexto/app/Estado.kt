package com.filtrotexto.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Estado compartido entre la pantalla y el servicio que hace el trabajo.
 * Vive mientras el proceso de la app esté vivo, aunque la pantalla se cierre.
 * Todos los cambios se hacen en el hilo principal (ver [actualizar]).
 */
object Estado {

    // Archivo elegido
    var archivoUri: Uri? = null
    var archivoNombre = ""
    var archivoTam = -1L

    // Trabajo en curso
    var trabajando = false
    @Volatile var cancelar = false
    var progreso = 0              // de 0 a 1000
    var indeterminada = false
    var mensaje = ""

    // Resultado listo para guardar
    var resultadoTemp: File? = null
    var resultadoCoinc = 0L
    var previa: List<String> = emptyList()
    var previaPalabras: List<String> = emptyList()
    var previaMayus = false
    var previaVersion = 0

    /** Para borrar temporales viejos una sola vez por inicio de la app. */
    var temporalesLimpiados = false

    /** La pantalla visible (si hay una) se registra acá para repintarse. */
    var oyente: (() -> Unit)? = null

    private val principal = Handler(Looper.getMainLooper())

    /** Ejecuta [cambio] en el hilo principal y luego avisa a la pantalla. */
    fun actualizar(cambio: () -> Unit) {
        principal.post {
            cambio()
            oyente?.invoke()
        }
    }

    fun limpiarResultado() {
        resultadoTemp?.delete()
        resultadoTemp = null
        resultadoCoinc = 0L
        previa = emptyList()
        previaVersion++
    }
}
