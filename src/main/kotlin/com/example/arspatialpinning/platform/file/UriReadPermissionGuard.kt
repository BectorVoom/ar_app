package com.example.arspatialpinning.platform.file

import android.content.ContentResolver
import android.net.Uri
import android.util.Log

interface UriReadPermissionGuard {
    fun <T> withReadPermission(uri: Uri, block: () -> T): T
}

object NoOpUriReadPermissionGuard : UriReadPermissionGuard {
    override fun <T> withReadPermission(uri: Uri, block: () -> T): T = block()
}

class ContentResolverUriReadPermissionGuard(
    private val contentResolver: ContentResolver
) : UriReadPermissionGuard {

    override fun <T> withReadPermission(uri: Uri, block: () -> T): T {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.close()
        } catch (error: SecurityException) {
            Log.d(TAG, "Temporary read-permission probe failed for uri=$uri", error)
        } catch (error: IllegalArgumentException) {
            Log.d(TAG, "Temporary read-permission probe failed for uri=$uri", error)
        } catch (error: IllegalStateException) {
            Log.d(TAG, "Temporary read-permission probe failed for uri=$uri", error)
        }
        return block()
    }

    private companion object {
        const val TAG = "UriPermissionGuard"
    }
}
