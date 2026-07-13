package com.example

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Modern, Ultra-Lightweight Android TV Launcher.
 * Designed for extreme performance (0ms UI lag) and offline-first/TV Box optimizations.
 * Includes a native asychronous Self-Updater.
 */
class MainActivity : AppCompatActivity() {

    // Constante URL del JSON de actualización (puedes cambiarla por tu propia URL)
    private val UPDATE_JSON_URL = "https://raw.githubusercontent.com/usuario/repo/main/update.json"

    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppAdapter

    // Componentes del Diálogo de Actualización
    private lateinit var dialogContainer: FrameLayout
    private lateinit var btnCancel: TextView
    private lateinit var btnUpdate: TextView
    private lateinit var progressContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var buttonContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar vistas principales
        rvApps = findViewById(R.id.rv_apps)
        dialogContainer = findViewById(R.id.dialog_container)
        btnCancel = findViewById(R.id.btn_cancel)
        btnUpdate = findViewById(R.id.btn_update)
        progressContainer = findViewById(R.id.progress_container)
        progressBar = findViewById(R.id.progress_bar)
        tvProgressPercent = findViewById(R.id.tv_progress_percent)
        buttonContainer = findViewById(R.id.button_container)

        // Configurar Grid Layout Manager (5 columnas para TV)
        rvApps.layoutManager = GridLayoutManager(this, 5)
        adapter = AppAdapter(emptyList()) { appInfo ->
            launchApp(appInfo)
        }
        rvApps.adapter = adapter

        // Cargar aplicaciones de manera asíncrona (evita congelar la UI)
        loadInstalledApps()

        // Ejecutar verificación de actualización en segundo plano
        checkForUpdates()
    }

    /**
     * Carga todas las aplicaciones instaladas de forma asíncrona en un hilo secundario
     */
    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) {
                val pm = packageManager
                val appsList = ArrayList<AppInfo>()
                val seenPackages = HashSet<String>()

                // 1. Buscar aplicaciones con categoría de TV (Leanback)
                val tvIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                }
                val tvApps = pm.queryIntentActivities(tvIntent, 0)
                for (resolveInfo in tvApps) {
                    val packageName = resolveInfo.activityInfo.packageName
                    if (seenPackages.add(packageName)) {
                        val name = resolveInfo.loadLabel(pm).toString()
                        val icon = resolveInfo.loadIcon(pm)
                        val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName)
                            ?: pm.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            appsList.add(AppInfo(name, packageName, icon, launchIntent))
                        }
                    }
                }

                // 2. Buscar aplicaciones estándar que no tengan categoría de TV pero sean lanzables
                val normalIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val normalApps = pm.queryIntentActivities(normalIntent, 0)
                for (resolveInfo in normalApps) {
                    val packageName = resolveInfo.activityInfo.packageName
                    if (seenPackages.add(packageName)) {
                        val name = resolveInfo.loadLabel(pm).toString()
                        val icon = resolveInfo.loadIcon(pm)
                        val launchIntent = pm.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            appsList.add(AppInfo(name, packageName, icon, launchIntent))
                        }
                    }
                }

                // Ordenar alfabéticamente por nombre de app
                appsList.sortBy { it.name.lowercase() }
                appsList
            }

            // Actualizar el adaptador y solicitar foco en la primera app
            adapter.updateData(apps)
            if (apps.isNotEmpty()) {
                rvApps.post {
                    val firstChild = rvApps.getChildAt(0)
                    firstChild?.requestFocus()
                }
            }
        }
    }

    /**
     * Lanza la aplicación seleccionada de forma instantánea
     */
    private fun launchApp(appInfo: AppInfo) {
        try {
            startActivity(appInfo.launchIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir ${appInfo.name}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Consulta el archivo JSON remoto de forma asíncrona
     */
    private fun checkForUpdates() {
        lifecycleScope.launch {
            val updateInfo = withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL(UPDATE_JSON_URL)
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(responseText)
                        val serverVersionCode = json.optInt("versionCode", 0)
                        val serverApkUrl = json.optString("apkUrl", "")
                        if (serverVersionCode > 0 && serverApkUrl.isNotEmpty()) {
                            UpdateInfo(serverVersionCode, serverApkUrl)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                } finally {
                    connection?.disconnect()
                }
            }

            if (updateInfo != null && updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                showUpdateDialog(updateInfo.apkUrl)
            }
        }
    }

    /**
     * Muestra el diálogo modal minimalista para la actualización
     */
    private fun showUpdateDialog(apkUrl: String) {
        dialogContainer.visibility = View.VISIBLE
        btnUpdate.requestFocus() // Dar foco por defecto al botón "Actualizar"

        btnCancel.setOnClickListener {
            dialogContainer.visibility = View.GONE
        }

        btnUpdate.setOnClickListener {
            startApkDownload(apkUrl)
        }
    }

    /**
     * Inicia la descarga en segundo plano y muestra la barra de progreso
     */
    private fun startApkDownload(apkUrl: String) {
        // Mostrar barra de progreso y ocultar botones de acción para evitar dobles clics
        buttonContainer.visibility = View.GONE
        progressContainer.visibility = View.VISIBLE
        progressBar.progress = 0
        tvProgressPercent.text = "0%"

        lifecycleScope.launch {
            val apkFile = withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                var inputStream: InputStream? = null
                var outputStream: FileOutputStream? = null
                try {
                    val url = URL(apkUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 15000
                    connection.connect()

                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        return@withContext null
                    }

                    val fileLength = connection.contentLength
                    val tempApkFile = File(cacheDir, "tv_launcher_update.apk")
                    if (tempApkFile.exists()) {
                        tempApkFile.delete()
                    }

                    inputStream = connection.inputStream
                    outputStream = FileOutputStream(tempApkFile)

                    val buffer = ByteArray(4096)
                    var totalRead: Long = 0
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        totalRead += bytesRead
                        if (fileLength > 0) {
                            val progress = (totalRead * 100 / fileLength).toInt()
                            withContext(Dispatchers.Main) {
                                progressBar.progress = progress
                                tvProgressPercent.text = "$progress%"
                            }
                        }
                        outputStream.write(buffer, 0, bytesRead)
                    }

                    tempApkFile
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                } finally {
                    outputStream?.close()
                    inputStream?.close()
                    connection?.disconnect()
                }
            }

            if (apkFile != null && apkFile.exists()) {
                // Ocultar diálogo al completar
                dialogContainer.visibility = View.GONE
                // Lanzar instalador del sistema compatible con Android 10 (API 29)
                installApk(apkFile)
            } else {
                Toast.makeText(this@MainActivity, "Descarga fallida. Reintente más tarde.", Toast.LENGTH_LONG).show()
                // Re-habilitar botones
                buttonContainer.visibility = View.VISIBLE
                progressContainer.visibility = View.GONE
            }
        }
    }

    /**
     * Utiliza FileProvider para lanzar de forma segura el instalador nativo del sistema
     */
    private fun installApk(apkFile: File) {
        try {
            val authority = "com.example.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(this, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al iniciar el instalador: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Modelo de datos para las aplicaciones en pantalla
    data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: Drawable,
        val launchIntent: Intent
    )

    // Modelo de datos para la información de actualización
    private data class UpdateInfo(
        val versionCode: Int,
        val apkUrl: String
    )

    /**
     * Adaptador clásico ultra-optimizado para RecyclerView sin lag
     */
    private class AppAdapter(
        private var apps: List<AppInfo>,
        private val onItemClick: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

        fun updateData(newApps: List<AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return AppViewHolder(view, onItemClick)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(apps[position])
        }

        override fun getItemCount(): Int = apps.size

        class AppViewHolder(
            itemView: View,
            private val onItemClick: (AppInfo) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
            private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)

            fun bind(app: AppInfo) {
                tvName.text = app.name
                ivIcon.setImageDrawable(app.icon)
                itemView.setOnClickListener {
                    onItemClick(app)
                }
            }
        }
    }
}
