package com.example.usbbackup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.usbbackup.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var backupService: BackupService? = null
    private var bound = false

    private var sourcePath: String = ""
    private var destTreeUri: Uri? = null

    private lateinit var prefs: SharedPreferences

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要存储权限才能读取文件", Toast.LENGTH_LONG).show()
        }
    }

    // 选择源文件夹 (使用 SAF 目录选择器)
    private val sourcePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            sourcePath = uriToPath(it)
            binding.tvSource.text = "源: $sourcePath"
            prefs.edit().putString("source_path", sourcePath).apply()
        }
    }

    // 选择 U 盘目标文件夹
    private val destPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destTreeUri = it
            binding.tvDest.text = "目标(U盘): ${it.lastPathSegment}"
            prefs.edit().putString("dest_uri", it.toString()).apply()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BackupService.LocalBinder
            backupService = binder.getService()
            bound = true
            setupServiceListener()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            backupService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)

        // 恢复上次配置
        sourcePath = prefs.getString("source_path", "") ?: ""
        val savedDest = prefs.getString("dest_uri", null)
        if (savedDest != null) destTreeUri = Uri.parse(savedDest)

        if (sourcePath.isNotEmpty()) binding.tvSource.text = "源: $sourcePath"
        destTreeUri?.let { binding.tvDest.text = "目标(U盘): ${it.lastPathSegment}" }

        setupButtons()
        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // 如果服务正在运行则绑定
        if (BackupService.isRunning) {
            bindService(Intent(this, BackupService::class.java), serviceConnection, 0)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun setupButtons() {
        binding.btnSelectSource.setOnClickListener {
            sourcePickerLauncher.launch(null)
        }

        binding.btnSelectDest.setOnClickListener {
            destPickerLauncher.launch(null)
        }

        binding.btnStartBackup.setOnClickListener {
            if (sourcePath.isEmpty()) {
                Toast.makeText(this, "请先选择要备份的文件夹", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (destTreeUri == null) {
                Toast.makeText(this, "请先选择 U 盘目标位置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startBackup()
        }

        binding.btnCancel.setOnClickListener {
            backupService?.cancel()
            binding.tvStatus.text = "正在取消..."
        }
    }

    private fun startBackup() {
        val intent = Intent(this, BackupService::class.java).apply {
            putExtra(BackupService.EXTRA_SOURCE, sourcePath)
            putExtra(BackupService.EXTRA_DEST_URI, destTreeUri.toString())
        }

        ContextCompat.startForegroundService(this, intent)
        bindService(Intent(this, BackupService::class.java), serviceConnection, 0)

        binding.tvStatus.text = "备份开始..."
        binding.progressBar.progress = 0
        binding.btnStartBackup.isEnabled = false
        binding.btnCancel.isEnabled = true
    }

    private fun setupServiceListener() {
        backupService?.progressListener = object : BackupProgressListener {
            override fun onFileStart(name: String, index: Int, total: Int) {
                runOnUiThread {
                    binding.tvStatus.text = "($index/$total) $name"
                    binding.progressBar.max = total
                    binding.progressBar.progress = index
                }
            }

            override fun onFileCopied(bytes: Long) {
                runOnUiThread {
                    val mb = bytes / 1024.0 / 1024.0
                    binding.tvBytes.text = String.format("已复制: %.1f MB", mb)
                }
            }

            override fun onSkipped(name: String) {}

            override fun onError(name: String, message: String) {
                runOnUiThread {
                    binding.tvLog.append("✗ $name: $message\n")
                }
            }

            override fun onFinished(copied: Int, skipped: Int, failed: Int, totalBytes: Long) {
                runOnUiThread {
                    val mb = totalBytes / 1024.0 / 1024.0
                    binding.tvStatus.text = "完成! 复制 $copied 个文件 (${String.format("%.1f", mb)} MB), 跳过 $skipped, 失败 $failed"
                    binding.btnStartBackup.isEnabled = true
                    binding.btnCancel.isEnabled = false
                }
            }
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            ).forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(it)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * 尝试将 SAF tree Uri 转换为实际文件路径
     * 对于内部存储 primary 目录有效
     */
    private fun uriToPath(uri: Uri): String {
        val path = uri.path ?: return uri.toString()
        // content://com.android.externalstorage.documents/tree/primary%3ADCIM
        if (path.contains("primary:")) {
            val subPath = path.substringAfter("primary:")
            return File(Environment.getExternalStorageDirectory(), subPath).absolutePath
        }
        // 如果是 U 盘等外置存储，返回原始路径描述
        return path.substringAfterLast("/").replace("%3A", "/")
    }
}
