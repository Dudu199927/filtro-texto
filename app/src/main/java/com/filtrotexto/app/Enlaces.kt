package com.filtrotexto.app

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/** Lectura de archivos desde un enlace de internet (sin Android, para poder probarla). */
object Enlaces {

    private val DRIVE_ARCHIVO = Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{10,})""")
    private val DRIVE_ID = Regex("""drive\.google\.com/(?:open|uc)\?(?:[^#]*&)?id=([A-Za-z0-9_-]{10,})""")

    /**
     * Devuelve el enlace listo para descargar, o null si no parece un enlace web.
     * Convierte los enlaces de "compartir" de Google Drive y Dropbox en enlaces directos.
     */
    fun normalizar(texto: String): String? {
        val t = texto.trim()
        val minus = t.lowercase()
        if (!(minus.startsWith("http://") || minus.startsWith("https://"))) return null
        if (t.contains(' ') || t.contains('\n')) return null
        try {
            URI(t)
        } catch (e: Exception) {
            return null
        }

        val drive = DRIVE_ARCHIVO.find(t) ?: DRIVE_ID.find(t)
        if (drive != null) {
            val id = drive.groupValues[1]
            return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        }

        val host = try {
            URI(t).host?.lowercase().orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (host == "dropbox.com" || host.endsWith(".dropbox.com")) {
            return when {
                Regex("""([?&])dl=0""").containsMatchIn(t) -> Regex("""([?&])dl=0""").replace(t, "$1dl=1")
                Regex("""([?&])dl=1""").containsMatchIn(t) -> t
                t.contains('?') -> "$t&dl=1"
                else -> "$t?dl=1"
            }
        }
        return t
    }

    /** Nombre del archivo según el enlace (lo último de la ruta); si no hay, el nombre del sitio. */
    fun nombreDe(url: String): String {
        return try {
            val u = URI(url)
            val ultimo = (u.path ?: "").trimEnd('/').substringAfterLast('/')
            val limpio = URLDecoder.decode(ultimo, "UTF-8")
            if (limpio.isNotEmpty() && limpio.contains('.')) limpio
            else if (limpio.isNotEmpty()) limpio
            else (u.host ?: "enlace")
        } catch (e: Exception) {
            "enlace"
        }
    }

    /** Resultado de abrir un enlace: el flujo de datos y su tamaño (-1 si el servidor no lo dice). */
    class Descarga(val flujo: InputStream, val tamano: Long, val nombreSugerido: String?)

    /** Abre el enlace siguiendo redirecciones. Lanza [IOException] con un mensaje para el usuario. */
    fun abrir(url: String): Descarga {
        var actual = url
        for (salto in 0..8) {
            val c = URL(actual).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FiltroTexto")
            // Sin compresión: así el tamaño que informa el servidor es el del archivo real.
            c.setRequestProperty("Accept-Encoding", "identity")

            val codigo = c.responseCode
            if (codigo in 300..399) {
                val destino = c.getHeaderField("Location")
                c.disconnect()
                if (destino.isNullOrEmpty()) throw IOException("El enlace redirige a un lugar inválido.")
                actual = URL(URL(actual), destino).toString()
                continue
            }
            if (codigo != 200) {
                c.disconnect()
                throw IOException("El servidor respondió $codigo. Revisá que el enlace sea directo y público.")
            }
            val tipo = (c.contentType ?: "").lowercase()
            if (tipo.startsWith("text/html")) {
                c.disconnect()
                throw IOException("El enlace abre una página web, no el archivo. Usá un enlace directo de descarga.")
            }
            val disposicion = c.getHeaderField("Content-Disposition")
            val nombre = disposicion?.let {
                Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
            }?.let { URLDecoder.decode(it, "UTF-8") }
            return Descarga(c.inputStream, c.contentLengthLong, nombre)
        }
        throw IOException("El enlace tiene demasiadas redirecciones.")
    }
}
