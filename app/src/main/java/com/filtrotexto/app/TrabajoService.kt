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
