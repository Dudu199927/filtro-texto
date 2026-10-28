package com.getkeepsafe.relinker;

import android.content.Context;

/**
 * Reemplazo mínimo de la librería ReLinker (que ya no se puede descargar).
 * a7zip la usa solo para cargar su librería nativa.
 */
public class ReLinker {
    public static void loadLibrary(Context context, String library) {
        System.loadLibrary(library);
    }
}
