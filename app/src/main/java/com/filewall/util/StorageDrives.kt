package com.filewall.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** What kind of storage a mounted volume is, as far as Android lets an app tell. */
enum class DriveKind(val label: String) {
    INTERNAL("Internal storage"),
    SD_CARD("SD card"),
    USB_HDD("USB HDD"),
    USB_SSD("USB SSD"),
    USB_SOLID("USB SSD / flash drive"),
    USB("USB drive (HDD / SSD / flash)"),
    OTHER("Storage"),
}

/** One mounted storage volume with its size and free space. [total] is 0 when Android hides it. */
data class StorageDrive(
    val name: String,
    val kind: DriveKind,
    val total: Long,
    val free: Long,
    val readOnly: Boolean,
) {
    val used: Long get() = (total - free).coerceAtLeast(0L)
}

/**
 * Every storage volume that is mounted right now: the phone's own storage, an SD card, and any
 * USB drive (flash stick, portable HDD or SSD) plugged in through OTG. Android reports a USB
 * drive as a removable volume without saying what it is, so the type is worked out from the
 * kernel's block device (SD cards are mmc, a spinning disk reports "rotational") and, failing
 * that, from the name the drive gives itself. When neither is conclusive the generic USB label
 * is shown rather than a guess.
 */
fun listStorageDrives(context: Context): List<StorageDrive> {
    val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        ?: return listOf(internalOnly())
    val drives = manager.storageVolumes.mapNotNull { volume ->
        val state = volume.state
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
            return@mapNotNull null
        }
        val description = volume.getDescription(context).orEmpty()
        val stat = volume.directoryOrNull()?.let { dir ->
            runCatching { StatFs(dir.path) }.getOrNull()
        }
        val kind = when {
            volume.isPrimary -> DriveKind.INTERNAL
            volume.isRemovable -> classifyRemovable(description, blockDeviceOf(volume))
            else -> DriveKind.OTHER
        }
        StorageDrive(
            name = if (kind == DriveKind.INTERNAL || description.isBlank()) kind.label else description,
            kind = kind,
            total = stat?.totalBytes ?: 0L,
            free = stat?.availableBytes ?: 0L,
            readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY,
        )
    }
    return drives.ifEmpty { listOf(internalOnly()) }
        .sortedBy { it.kind.ordinal }
}

/** Linux major number the kernel gives SD / MMC cards (mmcblk). USB disks are "sd" devices. */
private const val MMC_MAJOR = 179

private fun classifyRemovable(description: String, device: Pair<Int, Int>?): DriveKind {
    val name = description.lowercase()
    if (device?.first == MMC_MAJOR) return DriveKind.SD_CARD
    val isUsb = device != null || looksLikeUsb(description)
    if (!isUsb) return DriveKind.SD_CARD
    val rotational = device?.let { isRotational(it.first, it.second) }
    val hddWords = listOf("hdd", "hard disk", "hard drive", "passport", "elements", "backup plus", "expansion")
    return when {
        rotational == true || hddWords.any { it in name } -> DriveKind.USB_HDD
        "ssd" in name || "nvme" in name -> DriveKind.USB_SSD
        rotational == false -> DriveKind.USB_SOLID
        else -> DriveKind.USB
    }
}

/** (major, minor) of the block device behind a mounted volume, read from /proc/mounts if allowed. */
private fun blockDeviceOf(volume: StorageVolume): Pair<Int, Int>? {
    val uuid = volume.uuid ?: return null
    return runCatching {
        File("/proc/mounts").readLines().firstNotNullOfOrNull { line ->
            val parts = line.split(' ')
            if (parts.size > 1 && parts[0].contains("public:") && parts[1].contains(uuid, ignoreCase = true)) {
                val numbers = parts[0].substringAfter("public:").split(',')
                val major = numbers.getOrNull(0)?.toIntOrNull()
                val minor = numbers.getOrNull(1)?.toIntOrNull()
                if (major != null && minor != null) major to minor else null
            } else {
                null
            }
        }
    }.getOrNull()
}

/** True for a spinning disk, false for flash storage, null when the kernel won't say. */
private fun isRotational(major: Int, minor: Int): Boolean? = runCatching {
    val disk = minor - minor % 16 // the whole disk, not its first partition
    when (File("/sys/dev/block/$major:$disk/queue/rotational").readText().trim()) {
        "1" -> true
        "0" -> false
        else -> null
    }
}.getOrNull()

private fun looksLikeUsb(description: String): Boolean =
    description.contains("usb", ignoreCase = true) ||
        description.contains("otg", ignoreCase = true) ||
        description.contains("drive", ignoreCase = true) ||
        description.contains("disk", ignoreCase = true)

private fun StorageVolume.directoryOrNull(): File? =
    if (Build.VERSION.SDK_INT >= 30) {
        directory
    } else {
        // Before Android 11 the mount point is only exposed through a hidden getter.
        runCatching { StorageVolume::class.java.getMethod("getPath").invoke(this) as? String }
            .getOrNull()?.let(::File)
    }

private fun internalOnly(): StorageDrive {
    val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
    return StorageDrive(
        name = DriveKind.INTERNAL.label,
        kind = DriveKind.INTERNAL,
        total = stat?.totalBytes ?: 0L,
        free = stat?.availableBytes ?: 0L,
        readOnly = false,
    )
}
