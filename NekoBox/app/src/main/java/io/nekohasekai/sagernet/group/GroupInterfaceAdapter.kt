package io.nekohasekai.sagernet.group

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            var dialog: AlertDialog? = null
            cont.invokeOnCancellation {
                runOnMainDispatcher { dialog?.dismiss() }
            }
            runOnMainDispatcher {
                if (!cont.isActive) return@runOnMainDispatcher
                if (context.isFinishing || context.isDestroyed) {
                    if (cont.isActive) cont.resume(false)
                    return@runOnMainDispatcher
                }
                dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(R.string.yes) { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setNegativeButton(R.string.no) { _, _ -> if (cont.isActive) cont.resume(false) }
                    .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                    .setOnDismissListener { if (cont.isActive) cont.resume(false) }
                    .show()
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean
    ) {
        if (changed == 0 && duplicate.isEmpty()) {
            if (byUser) {
                onMainDispatcher {
                    if (!context.isFinishing && !context.isDestroyed) {
                        context.snackbar(
                            context.getString(
                                R.string.group_no_difference, group.displayName()
                            )
                        ).show()
                    }
                }
            }
        } else {
            onMainDispatcher {
                if (!context.isFinishing && !context.isDestroyed) {
                    context.snackbar(context.getString(R.string.group_updated, group.name, changed)).show()
                }
            }

            var status = ""
            if (added.isNotEmpty()) {
                status += context.getString(
                        R.string.group_added, added.joinToString("\n", postfix = "\n\n")
                )
            }
            if (updated.isNotEmpty()) {
                status += context.getString(R.string.group_changed,
                        updated.map { it }.joinToString("\n", postfix = "\n\n") {
                            if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                        })
            }
            if (deleted.isNotEmpty()) {
                status += context.getString(
                        R.string.group_deleted, deleted.joinToString("\n", postfix = "\n\n")
                )
            }
            if (duplicate.isNotEmpty()) {
                status += context.getString(
                        R.string.group_duplicate, duplicate.joinToString("\n", postfix = "\n\n")
                )
            }

            onMainDispatcher {
                delay(1000L)
                if (context.isFinishing || context.isDestroyed) return@onMainDispatcher

                MaterialAlertDialogBuilder(context).setTitle(
                        context.getString(
                                R.string.group_diff, group.displayName()
                        )
                ).setMessage(status.trim()).setPositiveButton(android.R.string.ok, null).show()
            }

        }

    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            if (!context.isFinishing && !context.isDestroyed) {
                context.snackbar(message).show()
            }
        }
    }

    override suspend fun alert(message: String) {
        return suspendCancellableCoroutine { cont ->
            var dialog: AlertDialog? = null
            cont.invokeOnCancellation {
                runOnMainDispatcher { dialog?.dismiss() }
            }
            runOnMainDispatcher {
                if (!cont.isActive) return@runOnMainDispatcher
                if (context.isFinishing || context.isDestroyed) {
                    if (cont.isActive) cont.resume(Unit)
                    return@runOnMainDispatcher
                }
                dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> if (cont.isActive) cont.resume(Unit) }
                    .setOnCancelListener { if (cont.isActive) cont.resume(Unit) }
                    .setOnDismissListener { if (cont.isActive) cont.resume(Unit) }
                    .show()
            }
        }
    }

}