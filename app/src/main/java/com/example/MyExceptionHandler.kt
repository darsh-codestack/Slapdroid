package com.example

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

class MyExceptionHandler(private val context: Context, private val defaultHandler: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        val file = File(context.filesDir, "crash_log.txt")
        try {
            val writer = PrintWriter(FileWriter(file, true))
            e.printStackTrace(writer)
            writer.flush()
            writer.close()
        } catch (ex: Exception) {
            Log.e("MyExceptionHandler", "Failed to write crash log", ex)
        }
        
        defaultHandler?.uncaughtException(t, e)
    }
}
