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
