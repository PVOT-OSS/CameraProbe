/*
 * SPDX-FileCopyrightText: Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.cameraprobe

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only access to the shared result zips in cache/share, granted per share intent. */
class ResultsProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val dir = shareDir(context!!).canonicalFile
        val file = File(dir, uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())).canonicalFile
        if (file.parentFile != dir || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only: $uri")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri) = "application/zip"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val AUTHORITY = "org.prauga.cameraprobe.results"

        fun shareDir(context: Context) = File(context.cacheDir, "share")

        fun uri(file: File): Uri = Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.name).build()
    }
}
