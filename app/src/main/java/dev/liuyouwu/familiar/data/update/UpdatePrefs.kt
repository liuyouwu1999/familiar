package dev.liuyouwu.familiar.data.update

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey

/** 更新功能持久化键（存于 settings DataStore，与 SettingsRepository 共用同一实例） */
internal val KEY_UPDATE_AUTO_CHECK = booleanPreferencesKey("tw_update_auto_check")
internal val KEY_UPDATE_LAST_CHECK_MILLIS = longPreferencesKey("tw_update_last_check_millis")
internal val KEY_UPDATE_DOWNLOAD_ID = longPreferencesKey("tw_update_download_id")
internal val KEY_UPDATE_PENDING_VERSION = stringPreferencesKey("tw_update_pending_version")
