package com.github.ashutoshgngwr.noice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioAttributesCompat
import com.github.ashutoshgngwr.noice.R
import com.github.ashutoshgngwr.noice.engine.PlaybackController
import com.github.ashutoshgngwr.noice.model.Preset
import com.github.ashutoshgngwr.noice.models.Alarm
import com.github.ashutoshgngwr.noice.repository.AlarmRepository
import com.github.ashutoshgngwr.noice.repository.PresetRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.random.Random

@AndroidEntryPoint
class AlarmRingerService : LifecycleService() {

  @set:Inject
  internal lateinit var alarmRepository: AlarmRepository

  @set:Inject
  internal lateinit var presetRepository: PresetRepository

  @set:Inject
  internal lateinit var playbackController: PlaybackController

  @set:Inject
  internal lateinit var uiController: UiController

  private val notificationManager: NotificationManager by lazy { requireNotNull(getSystemService()) }
  private val vibrator: Vibrator by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      requireNotNull(getSystemService<VibratorManager>()).defaultVibrator
    } else {
      requireNotNull(getSystemService())
    }
  }

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createNotificationChannel(
        channelId = PRIMARY_CHANNEL_ID,
        importance = NotificationManager.IMPORTANCE_HIGH,
        nameResId = R.string.notification_channel_alarm_primary__name,
        descriptionResId = R.string.notification_channel_alarm_primary__description,
      )

      createNotificationChannel(
        channelId = SECONDARY_CHANNEL_ID,
        importance = NotificationManager.IMPORTANCE_HIGH,
        nameResId = R.string.notification_channel_alarm_secondary__name,
        descriptionResId = R.string.notification_channel_alarm_secondary__description,
        soundUri = Settings.System.DEFAULT_ALARM_ALERT_URI,
        audioAttributes = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setLegacyStreamType(AudioManager.STREAM_ALARM)
          .build(),
      )

      createNotificationChannel(
        channelId = PRIMING_CHANNEL_ID,
        importance = NotificationManager.IMPORTANCE_LOW,
        nameResId = R.string.notification_channel_alarm_priming__name,
        descriptionResId = R.string.notification_channel_alarm_priming__description,
      )
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    intent ?: return super.onStartCommand(null, flags, startId)
    val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
    when (intent.action) {
      ACTION_RING -> lifecycleScope.launch { startRinger(alarmId) }
      ACTION_SNOOZE -> lifecycleScope.launch { snooze(alarmId) }
      ACTION_DISMISS -> lifecycleScope.launch { dismiss(alarmId) }
    }

    return super.onStartCommand(intent, flags, startId)
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel(
    channelId: String,
    importance: Int,
    @StringRes nameResId: Int,
    @StringRes descriptionResId: Int,
    soundUri: Uri? = null,
    audioAttributes: AudioAttributes? = null,
  ) {
    NotificationChannel(channelId, getString(nameResId), importance)
      .apply {
        description = getString(descriptionResId)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        enableVibration(false)
        setShowBadge(false)
        setSound(soundUri, audioAttributes)
      }
      .also { notificationManager.createNotificationChannel(it) }
  }

  private suspend fun startRinger(alarmId: Int) {
    val alarm = withContext(Dispatchers.IO) { alarmRepository.get(alarmId) }
    if (alarm == null) {
      withContext(Dispatchers.Main) { stopSelf() }
      return
    }

    val timeFmtFlags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_TIME
    val alarmTriggerTime = DateUtils.formatDateTime(this, System.currentTimeMillis(), timeFmtFlags)

    // since loading the preset may take some time, show a notification.
    withContext(Dispatchers.Main) {
      startForeground(NOTIFICATION_ID_PRIMING, buildLoadingNotification(alarmTriggerTime))
    }

    val preset: Preset? = withContext(Dispatchers.IO) {
      alarm.preset // use the alarm's selected preset if it has one
        ?: presetRepository.list().randomOrNull() // or pick one at random from the saved presets
        ?: presetRepository.generate(emptySet(), Random.nextInt(2, 6))
          .lastOrNull()?.data // or attempt to generate one
    }

    if (alarm.vibrate) {
      startVibrating()
    }

    if (preset == null) {
      // if we couldn't get a preset to play, start the ringer with default alarm ringtone.
      withContext(Dispatchers.Main) {
        ServiceCompat.stopForeground(this@AlarmRingerService, ServiceCompat.STOP_FOREGROUND_DETACH)
        buildRingerNotification(SECONDARY_CHANNEL_ID, alarm, alarmTriggerTime)
          .also { startForeground(NOTIFICATION_ID_ALARM, it) }

        buildPresetLoadFailedNotification(alarmTriggerTime)
          .also { notificationManager.notify(NOTIFICATION_ID_PRIMING, it) }
      }
      return
    }

    withContext(Dispatchers.Main) {
      ServiceCompat.stopForeground(this@AlarmRingerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
      buildRingerNotification(PRIMARY_CHANNEL_ID, alarm, alarmTriggerTime)
        .also { startForeground(NOTIFICATION_ID_ALARM, it) }

      playbackController.setAudioUsage(AudioAttributesCompat.USAGE_ALARM)
      playbackController.play(preset)
    }
  }

  private fun buildLoadingNotification(contentText: String): Notification {
    return NotificationCompat.Builder(this, PRIMING_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_baseline_alarm_24)
      .setContentTitle(getString(R.string.alarm))
      .setContentText(contentText)
      .setProgress(0, 0, true)
      .setOngoing(true)
      .setShowWhen(false)
      .build()
  }

  private fun startVibrating() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_ALARM)
        .build()
        .also { vibrator.vibrate(VibrationEffect.createWaveform(DEFAULT_VIBRATION_PATTERN, 0), it) }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .build()
        .also {
          @Suppress("DEPRECATION")
          vibrator.vibrate(VibrationEffect.createWaveform(DEFAULT_VIBRATION_PATTERN, 0), it)
        }
    } else {
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .build()
        .also { @Suppress("DEPRECATION") vibrator.vibrate(DEFAULT_VIBRATION_PATTERN, 0, it) }
    }
  }

  private fun buildRingerNotification(
    channelId: String,
    alarm: Alarm,
    alarmTriggerTime: String,
  ): Notification {
    return NotificationCompat.Builder(this, channelId)
      .setSmallIcon(R.drawable.ic_baseline_alarm_24)
      .setContentTitle(getString(R.string.alarm))
      .setContentText(alarmTriggerTime)
      .addAction(
        R.drawable.ic_round_snooze_24,
        getString(R.string.snooze),
        buildPendingServiceIntent(buildSnoozeIntent(this, alarm.id), 0x61),
      )
      .addAction(
        R.drawable.ic_round_close_24,
        getString(R.string.dismiss),
        buildPendingServiceIntent(buildDismissIntent(this, alarm.id), 0x62),
      )
      .setFullScreenIntent(
        uiController.buildShowIntent(alarm.id, alarm.label, alarmTriggerTime)
          .let { PendingIntent.getActivity(this, 0x63, it, PI_FLAGS) },
        true,
      )
      .setShowWhen(false)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .build()
  }

  private fun buildPendingServiceIntent(intent: Intent, requestCode: Int): PendingIntent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      PendingIntent.getForegroundService(this, requestCode, intent, PI_FLAGS)
    } else {
      PendingIntent.getService(this, requestCode, intent, PI_FLAGS)
    }
  }

  private fun buildPresetLoadFailedNotification(alarmTime: String): Notification {
    return NotificationCompat.Builder(this, PRIMING_CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_baseline_alarm_24)
      .setContentTitle(getString(R.string.alarm))
      .setContentText(getString(R.string.alarm_ringer_preset_load_error))
      .setSubText(alarmTime)
      .setShowWhen(true)
      .setAutoCancel(true)
      .build()
  }

  private suspend fun dismiss(alarmId: Int) {
    withContext(Dispatchers.IO) { alarmRepository.reportTrigger(alarmId, false) }
    withContext(Dispatchers.Main) { dismissRinger() }
  }

  private suspend fun snooze(alarmId: Int) {
    withContext(Dispatchers.IO) { alarmRepository.reportTrigger(alarmId, true) }
    withContext(Dispatchers.Main) { dismissRinger() }
  }

  private fun dismissRinger() {
    vibrator.cancel()
    playbackController.pause(true)
    playbackController.setAudioUsage(AudioAttributesCompat.USAGE_MEDIA)

    uiController.dismiss()
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  companion object {
    private const val ACTION_RING = "ring"
    private const val ACTION_SNOOZE = "snooze"
    private const val ACTION_DISMISS = "dismiss"
    private const val EXTRA_ALARM_ID = "alarmId"

    private const val PRIMARY_CHANNEL_ID = "com.github.ashutoshgngwr.noice.alarms"
    private const val SECONDARY_CHANNEL_ID = "com.github.ashutoshgngwr.noice.alarmsFallback"
    private const val PRIMING_CHANNEL_ID = "com.github.ashutoshgngwr.noice.alarmPriming"
    private const val NOTIFICATION_ID_PRIMING = 0x3
    private const val NOTIFICATION_ID_ALARM = 0x4

    private val DEFAULT_VIBRATION_PATTERN = longArrayOf(500, 500, 500, 500, 500)
    private val PI_FLAGS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else PendingIntent.FLAG_UPDATE_CURRENT

    fun buildRingIntent(context: Context, alarmId: Int): Intent {
      return Intent(context, AlarmRingerService::class.java)
        .setAction(ACTION_RING)
        .putExtra(EXTRA_ALARM_ID, alarmId)
    }

    fun buildSnoozeIntent(context: Context, alarmId: Int): Intent {
      return Intent(context, AlarmRingerService::class.java)
        .setAction(ACTION_SNOOZE)
        .putExtra(EXTRA_ALARM_ID, alarmId)
    }

    fun buildDismissIntent(context: Context, alarmId: Int): Intent {
      return Intent(context, AlarmRingerService::class.java)
        .setAction(ACTION_DISMISS)
        .putExtra(EXTRA_ALARM_ID, alarmId)
    }
  }

  interface UiController {
    fun buildShowIntent(alarmId: Int, alarmLabel: String?, alarmTriggerTime: String): Intent
    fun dismiss()
  }
}
