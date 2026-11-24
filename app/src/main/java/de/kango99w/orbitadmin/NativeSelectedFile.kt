package de.kango99w.orbitadmin

import android.net.Uri

/** Transient Android Storage Access Framework selection. No file content or URI is sent yet. */
data class NativeSelectedFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long
)
