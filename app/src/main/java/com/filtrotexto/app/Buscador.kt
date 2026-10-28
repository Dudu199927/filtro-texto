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
