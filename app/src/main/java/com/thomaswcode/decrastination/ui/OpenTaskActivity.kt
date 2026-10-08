package com.thomaswcode.decrastination.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.thomaswcode.decrastination.AppGraph
import kotlinx.coroutines.launch

/**
 * Opens one task where it lives, then gets out of the way: what the widget and notifications
 * start, since only an activity may start another app's. Without a task, it opens the app.
 */
class OpenTaskActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        val task = intent.getStringExtra(EXTRA_TASK_ID)?.let { id -> graph.tasks.value.tasks.firstOrNull { it.id == id && it.isOpen } }
        if (task == null) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        lifecycleScope.launch {
            TaskOpener.open(this@OpenTaskActivity, task)?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_LONG).show() }
            finish()
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"

        fun intent(context: Context, taskId: String?): Intent =
            Intent(context, OpenTaskActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
