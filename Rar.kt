package com.filtrotexto.app

import android.content.Context
import com.hippo.a7zip.A7Zip
import com.hippo.a7zip.ArchiveException
import com.hippo.a7zip.InArchive
import com.hippo.a7zip.PasswordException
import com.hippo.a7zip.PropID
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Conecta el buscador con la librería a7zip (7-Zip para Android), que lee RAR, RAR5 y 7z. */
object Rar {
    private const val MSG_CLAVE = "El archivo tiene contraseña; todavía no se admiten."

    @Volatile
    private var listo = false

    fun abridor(contexto: Context): (File) -> ArchivoRar {
        val app = contexto.applicationContext
        return { archivo ->
            try {
                if (!listo) {
                    A7Zip.initialize(app)
                    listo = true
                }
                Envoltura(InArchive.open(archivo))
            } catch (e: PasswordException) {
                throw FormatoNoAdmitido(MSG_CLAVE)
            } catch (e: ArchiveException) {
                throw FormatoNoAdmitido("No se pudo abrir el archivo (¿está dañado o es de otro tipo?).")
            } catch (e: UnsatisfiedLinkError) {
                throw FormatoNoAdmitido("Este teléfono no puede leer RAR / 7z.")
            }
        }
    }

    private class Envoltura(private val a: InArchive) : ArchivoRar {
