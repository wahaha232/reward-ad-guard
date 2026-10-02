package com.rewardadguard.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rewardadguard.app.R
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy
import com.rewardadguard.app.manager.LogExporter

/**
 * Enum -> localised label mapping.
 *
 * The enum names in the `data` package are a STORAGE format: they are written
 * into Room rows and DataStore entries and `fromName()` parses them back.
 * Renaming or translating them would silently corrupt existing data and break
 * the `EventType.entries.firstOrNull { it.name == ... }` lookups in LogScreen,
 * so the display layer resolves a resource id instead. Keeping the mapping in
 * one file means a new enum constant cannot be forgotten in three screens at
 * once - each `when` below is exhaustive without an `else` branch, so the
 * compiler fails the build if a constant is added later.
 */

@get:StringRes
val ProtectionMode.labelRes: Int
    get() = when (this) {
        ProtectionMode.OFF -> R.string.protection_mode_off
        ProtectionMode.LOG_ONLY -> R.string.protection_mode_log_only
        ProtectionMode.BLOCK -> R.string.protection_mode_block
    }

@get:StringRes
val RedirectPolicy.labelRes: Int
    get() = when (this) {
        RedirectPolicy.LOG_ONLY -> R.string.redirect_policy_log_only
        RedirectPolicy.BLOCK_IMMEDIATELY -> R.string.redirect_policy_block_immediately
        RedirectPolicy.BLOCK_AFTER_GRACE -> R.string.redirect_policy_block_after_grace
    }

@get:StringRes
val AssistAction.labelRes: Int
    get() = when (this) {
        AssistAction.NONE -> R.string.assist_action_none
        AssistAction.ASSIST_CLICK -> R.string.assist_action_assist_click
        AssistAction.ASSIST_WHEN_IDLE -> R.string.assist_action_assist_when_idle
        AssistAction.ASSIST_TIMED_RETRY -> R.string.assist_action_assist_timed_retry
    }

@get:StringRes
val LogExporter.Format.labelRes: Int
    get() = when (this) {
        LogExporter.Format.TXT -> R.string.export_format_txt
        LogExporter.Format.CSV -> R.string.export_format_csv
        LogExporter.Format.JSON -> R.string.export_format_json
    }

@get:StringRes
val LogFilter.labelRes: Int
    get() = when (this) {
        LogFilter.ALL -> R.string.log_filter_all
        LogFilter.REDIRECTS -> R.string.log_filter_redirects
        LogFilter.BLOCKS -> R.string.log_filter_blocks
        LogFilter.CLOSE -> R.string.log_filter_close
        LogFilter.RETURNS -> R.string.log_filter_returns
        LogFilter.SESSIONS -> R.string.log_filter_sessions
        LogFilter.ERRORS -> R.string.log_filter_errors
    }

/** Composable convenience wrappers so the call sites stay short. */
@Composable
fun ProtectionMode.label(): String = stringResource(labelRes)

@Composable
fun RedirectPolicy.label(): String = stringResource(labelRes)

@Composable
fun AssistAction.label(): String = stringResource(labelRes)

@Composable
fun LogExporter.Format.label(): String = stringResource(labelRes)

@Composable
fun LogFilter.label(): String = stringResource(labelRes)
