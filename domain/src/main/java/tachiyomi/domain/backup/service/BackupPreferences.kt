package tachiyomi.domain.backup.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class BackupPreferences(
    preferenceStore: PreferenceStore,
) {

    val backupInterval: Preference<Int> = preferenceStore.getInt("backup_interval", 12)

    /** Hay una cuenta de Google autorizada para guardar copias en Google Drive. */
    val googleDriveConnected: Preference<Boolean> = preferenceStore.getBoolean("google_drive_connected", false)

    /** Subir a Google Drive cada copia automática. */
    val googleDriveAutoUpload: Preference<Boolean> = preferenceStore.getBoolean("google_drive_auto_upload", true)

    val lastAutoBackupTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_auto_backup_timestamp"),
        0L,
    )
}
