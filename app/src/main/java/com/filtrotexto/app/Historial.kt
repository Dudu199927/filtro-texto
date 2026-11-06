package com.filtrotexto.app

import android.content.Context

/** Una búsqueda guardada: lo que se escribió y las opciones marcadas. */
class Busqueda(
    val palabras: String,
    val excluir: String,
    val todas: Boolean,
    val mayus: Boolean,
    val latin1: Boolean
) {
    /** Dos búsquedas son la misma si tienen lo mismo escrito y las mismas opciones. */
    fun igualA(o: Busqueda) =
        palabras == o.palabras && excluir == o.excluir && todas == o.todas &&
            mayus == o.mayus && latin1 == o.latin1

    /** Texto para la lista: las palabras, y debajo las opciones que no son las normales. */
    fun titulo(): String {
        val extra = ArrayList<String>()
        if (excluir.isNotEmpty()) extra.add("excluir: $excluir")
        if (todas) extra.add("todas las palabras")
        if (mayus) extra.add("distingue mayúsculas")
        if (latin1) extra.add("Latin-1")
        return if (extra.isEmpty()) palabras else palabras + "\n" + extra.joinToString(" · ")
    }

    fun codificar(): String =
        listOf(palabras, excluir, if (todas) "1" else "0", if (mayus) "1" else "0", if (latin1) "1" else "0")
            .joinToString(SEP_CAMPO)

    companion object {
        const val SEP_CAMPO = "\u0001"

        fun decodificar(s: String): Busqueda? {
            val c = s.split(SEP_CAMPO)
            if (c.size != 5 || c[0].isEmpty()) return null
            return Busqueda(c[0], c[1], c[2] == "1", c[3] == "1", c[4] == "1")
        }
    }
}

/** Guarda las últimas búsquedas en el teléfono (la más reciente primero). */
object Historial {
    const val MAXIMO = 10
    private const val ARCHIVO = "historial"
    private const val CLAVE = "busquedas"
    private const val SEP_ENTRADA = "\u0002"

    fun leer(ctx: Context): List<Busqueda> {
        return try {
            val texto = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).getString(CLAVE, "") ?: ""
            if (texto.isEmpty()) emptyList()
            else texto.split(SEP_ENTRADA).mapNotNull { Busqueda.decodificar(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Pone la búsqueda primera; si ya estaba, no se repite. Se guardan hasta [MAXIMO]. */
    fun guardar(ctx: Context, b: Busqueda) {
        try {
            val lista = ArrayList<Busqueda>()
            lista.add(b)
            for (x in leer(ctx)) if (!x.igualA(b)) lista.add(x)
            val recorte = lista.take(MAXIMO)
            ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit()
                .putString(CLAVE, recorte.joinToString(SEP_ENTRADA) { it.codificar() })
                .apply()
        } catch (e: Exception) {
            // el historial es una comodidad; si falla, la búsqueda sigue igual
        }
    }

    fun borrar(ctx: Context) {
        try {
            ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit().remove(CLAVE).apply()
        } catch (e: Exception) {
            // no es grave
        }
    }
}

/** Un archivo abierto antes: dirección, nombre y tamaño (-1 si no se sabe). */
class ArchivoReciente(val uri: String, val nombre: String, val tam: Long) {
    fun codificar(): String = listOf(uri, nombre, tam.toString()).joinToString(Busqueda.SEP_CAMPO)

    companion object {
        fun decodificar(s: String): ArchivoReciente? {
            val c = s.split(Busqueda.SEP_CAMPO)
            if (c.size != 3 || c[0].isEmpty()) return null
            return ArchivoReciente(c[0], c[1], c[2].toLongOrNull() ?: -1L)
        }
    }
}

/** Guarda los últimos archivos abiertos (el más reciente primero). */
object ArchivosRecientes {
    const val MAXIMO = 10
    private const val ARCHIVO = "historial"
    private const val CLAVE = "archivos"
    private const val SEP_ENTRADA = "\u0002"

    fun leer(ctx: Context): List<ArchivoReciente> {
        return try {
            val texto = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).getString(CLAVE, "") ?: ""
            if (texto.isEmpty()) emptyList()
            else texto.split(SEP_ENTRADA).mapNotNull { ArchivoReciente.decodificar(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun escribir(ctx: Context, lista: List<ArchivoReciente>) {
        val e = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit()
        if (lista.isEmpty()) e.remove(CLAVE) else e.putString(CLAVE, lista.joinToString(SEP_ENTRADA) { it.codificar() })
        e.apply()
    }

    /** Pone el archivo primero. Devuelve los que quedaron fuera de la lista (para soltar su permiso). */
    fun guardar(ctx: Context, a: ArchivoReciente): List<ArchivoReciente> {
        return try {
            val lista = ArrayList<ArchivoReciente>()
            lista.add(a)
            for (x in leer(ctx)) if (x.uri != a.uri) lista.add(x)
            escribir(ctx, lista.take(MAXIMO))
            lista.drop(MAXIMO)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun quitar(ctx: Context, uri: String) {
        try {
            escribir(ctx, leer(ctx).filter { it.uri != uri })
        } catch (e: Exception) {
            // no es grave
        }
    }

    fun borrar(ctx: Context) {
        try {
            escribir(ctx, emptyList())
        } catch (e: Exception) {
            // no es grave
        }
    }
}
