package ru.is74.door.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import ru.is74.door.MainActivity
import ru.is74.door.R
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/** Persistent request stores only door identity, range and the user-chosen output URI. */
class VideoExportWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    private val notifications=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var lastProgressAt=0L

    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val destination=inputData.getString("uri")?.let(Uri::parse) ?: return@withContext Result.failure()
        val doorId=inputData.getString("doorId") ?: return@withContext Result.failure()
        val from=Instant.ofEpochSecond(inputData.getLong("from",0))
        val to=Instant.ofEpochSecond(inputData.getLong("to",0))
        val folder=File(applicationContext.cacheDir,"video-export-$id")
        var copied=false
        var failed=false
        try {
            require(from<to) { "Выберите конец позже начала" }
            folder.deleteRecursively();check(folder.mkdirs())
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Сохранение видео",NotificationManager.IMPORTANCE_LOW))
            setForeground(foreground("Подготавливаем видео",0,true))
            report("Подготавливаем видео",0,force=true)
            val repository=DoorRepository(applicationContext)
            val session=repository.currentSession() ?: throw ApiException("Войдите в аккаунт для сохранения видео")
            if(session.userId!=inputData.getString("userId")) throw ApiException("Аккаунт изменился. Запустите сохранение заново")
            val door=repository.doors().firstOrNull { it.id==doorId } ?: throw ApiException("Нет доступа к выбранному домофону")
            val camera=repository.camera(door)
            if(!camera.canArchive) throw ApiException(camera.archiveReason.ifBlank { "Нет доступа к архиву" })
            val exporter=HlsExporter()
            val headers=repository.playerHeaders()
            val plan=exporter.prepare(camera,from,to,headers) { done,total ->
                report("Читаем архив: $done из $total",if(total>0) done*5/total else 0)
            }
            val segments=exporter.download(plan,folder,headers) { done,total,bytes ->
                report("Скачиваем: $done из $total · ${bytes/1_048_576} МБ",5+if(total>0) done*75/total else 0)
            }
            coroutineContext.ensureActive()
            val sourceBytes=segments.sumOf { it.file.length() }
            if(folder.usableSpace < sourceBytes+32L*1_048_576) throw ApiException("Недостаточно места для сборки MP4. Освободите память телефона")
            val output=File(folder,"video.mp4")
            muxSegments(segments,output) { done,total ->
                report("Собираем MP4: $done из $total",80+if(total>0) done*15/total else 0)
            }
            coroutineContext.ensureActive()
            // The finished MP4 is self-contained. Free the TS files before creating its final copy.
            segments.forEach { it.file.delete() }
            report("Сохраняем файл",96,force=true)
            applicationContext.contentResolver.openOutputStream(destination,"wt")?.use { sink ->
                output.inputStream().use { source ->
                    val buffer=ByteArray(128*1024)
                    while(true) {
                        coroutineContext.ensureActive()
                        val count=source.read(buffer);if(count<0) break
                        sink.write(buffer,0,count)
                    }
                    sink.flush()
                }
            } ?: throw ApiException("Не удалось открыть выбранную папку")
            copied=true
            val format=DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(CameraZone)
            val details="${format.format(plan.effectiveStart)} — ${format.format(plan.effectiveEnd)} · UTC+5" +
                if(plan.gapDurationUs>1_000_000) "\nВ архиве есть пропуски: сохранены доступные фрагменты." else ""
            Result.success(workDataOf("uri" to destination.toString(),"message" to details,"bytes" to output.length(),"percent" to 100))
        } catch(e:CancellationException) {
            throw e
        } catch(e:Exception) {
            failed=true
            val message=when(e) {
                is ApiException -> e.message ?: "Не удалось сохранить видео"
                is SegmentMuxException -> e.message ?: "Не удалось собрать MP4"
                else -> "Не удалось сохранить MP4. Проверьте свободное место и подключение и повторите."
            }
            Result.failure(workDataOf("message" to message))
        } finally {
            withContext(NonCancellable+Dispatchers.IO) {
                folder.deleteRecursively()
                if(!copied) runCatching {
                    if(failed || (Build.VERSION.SDK_INT>=31 && stopReason==WorkInfo.STOP_REASON_CANCELLED_BY_APP))
                        DocumentsContract.deleteDocument(applicationContext.contentResolver,destination)
                    else applicationContext.contentResolver.openOutputStream(destination,"wt")?.close()
                }
            }
        }
    }

    private fun report(text:String,percent:Int,force:Boolean=false) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(!force && now-lastProgressAt<400) return
        lastProgressAt=now
        setProgressAsync(workDataOf("message" to text,"percent" to percent))
        setForegroundAsync(foreground(text,percent,percent==0))
    }
    private fun foreground(text:String,percent:Int,indeterminate:Boolean):ForegroundInfo {
        val open=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(applicationContext,CHANNEL)
            .setSmallIcon(R.drawable.ic_download).setContentTitle("Сохранение видео")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100,percent,indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Отменить",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(NOTIFICATION,notification)
    }
    companion object {
        const val UNIQUE_NAME="camera-video-export"
        private const val CHANNEL="video-export"
        private const val NOTIFICATION=7401
    }
}
