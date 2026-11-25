package com.openapk.translate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

object InstallEvents {
    @Volatile
    var listener: ((String) -> Unit)? = null
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val extra = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val msg = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                return
            }
            PackageInstaller.STATUS_SUCCESS -> "✅ Установлено! Открывай и проверяй 🎉"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "❌ Конфликт: стоит оригинал с другой подписью. Нажми «Удалить оригинал», потом «Установить»."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "❌ Несовместимо с этим устройством. $extra"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "❌ Не хватает памяти. $extra"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "❌ Установка заблокирована системой. $extra"
            else -> "❌ Установка не удалась: $extra"
        }
        val l = InstallEvents.listener
        if (l != null) l(msg) else Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
