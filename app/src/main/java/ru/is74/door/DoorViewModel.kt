package ru.is74.door

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.*
import ru.is74.door.data.*
import java.time.*
import java.util.UUID

val CameraZone: ZoneId = ZoneId.of("Asia/Yekaterinburg")

data class DoorState(
    val signedIn: Boolean = false, val demo: Boolean = false,
    val authStep: Int = 0, val phone: String = "", val code: String = "",
    val challenge: AuthChallenge? = null, val confirmed: ConfirmedAuth? = null,
    val resendAt: Long = 0, val now: Long = Instant.now().epochSecond,
    val busy: Boolean = false, val loadingDay: Boolean = false,
    val error: String? = null, val notice: String? = null,
    val doors: List<Door> = emptyList(), val door: Door? = null, val camera: Camera? = null,
    val date: LocalDate = LocalDate.now(CameraZone),
    val events: List<DoorEvent> = emptyList(), val ranges: List<RecordingRange> = emptyList(),
    val eventError: String? = null, val archiveError: String? = null,
    val selected: Long = Instant.now().epochSecond, val live: Boolean = true,
    val url: String? = null, val streamRequest: Long? = null, val playerGeneration: Int = 0,
    val playing: Boolean = true, val muted: Boolean = true, val speed: Float = 1f,
    val opening: Boolean = false, val openCooldownUntil: Long = 0,
    val filter: String = "all"
)

data class ExportState(val phase:String="none",val percent:Int=0,val message:String="",val uri:String?=null) {
    val active:Boolean get()=phase in setOf("queued","running")
}

class DoorViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DoorRepository(app)
    var state by mutableStateOf(DoorState(signedIn = repo.currentSession() != null))
        private set
    private var cameraJob: Job? = null
    private var dayJob: Job? = null
    private var authJob: Job? = null
    private var openJob: Job? = null
    private var exportObserver:Job?=null
    private var exportEnqueueJob:Job?=null
    private val workManager=WorkManager.getInstance(app)
    private val exportPrefs=app.getSharedPreferences("video_exports",0)
    var export by mutableStateOf(ExportState())
        private set
    val headers: Map<String, String> get() = if(state.demo) emptyMap() else repo.playerHeaders()

    init {
        exportPrefs.getString("workId",null)?.let { runCatching { observeExport(UUID.fromString(it)) } }
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val now = Instant.now().epochSecond
                state = state.copy(now = now, selected = if(state.live) now else state.selected)
            }
        }
        if (state.signedIn) refreshDoors()
    }

    fun phone(value: String) { state = state.copy(phone = value, error = null) }
    fun code(value: String) { state = state.copy(code = value.filter(Char::isDigit).take(10), error = null) }
    fun clearMessage() { state = state.copy(error = null, notice = null) }
    fun backToPhone() { authJob?.cancel(); state = state.copy(authStep=0, code="", error=null, busy=false, confirmed=null) }
    private fun normalizedPhone(): String {
        val digits = state.phone.filter(Char::isDigit)
        return if(digits.length == 11 && digits.first() in "78") digits.drop(1) else digits
    }

    fun requestCode() {
        if(state.busy || state.now < state.resendAt) return
        val phone = normalizedPhone()
        if(phone.length != 10) { state=state.copy(error="Введите российский номер: 10 цифр после +7."); return }
        state = state.copy(busy=true, error=null)
        authJob = viewModelScope.launch {
            try {
                val challenge = repo.requestCode(phone)
                state=state.copy(busy=false, phone=phone, challenge=challenge, authStep=1,
                    resendAt=Instant.now().epochSecond + challenge.retryAfterSeconds, code="")
            } catch(e: Exception) { failure(e) }
        }
    }

    fun confirmCode() {
        if(state.busy) return
        if(state.code.length < 4) { state=state.copy(error="Введите код из звонка или сообщения."); return }
        state=state.copy(busy=true,error=null)
        authJob=viewModelScope.launch {
            try {
                val confirmed=repo.confirmCode(normalizedPhone(),state.code)
                state=state.copy(confirmed=confirmed,busy=false,authStep=2)
                if(confirmed.accounts.size==1) signIn(confirmed.accounts.single())
            } catch(e:Exception) { failure(e) }
        }
    }

    fun signIn(account: Account) {
        val authId=state.confirmed?.authId ?: return
        if(state.busy) return
        state=state.copy(busy=true,error=null)
        authJob=viewModelScope.launch {
            try {
                repo.signIn(authId,account)
                state=DoorState(signedIn=true)
                refreshDoors()
            } catch(e:Exception) { failure(e) }
        }
    }

    fun refreshDoors() {
        if(state.demo) return
        cameraJob?.cancel(); dayJob?.cancel()
        state=state.copy(busy=true,error=null)
        cameraJob=viewModelScope.launch {
            try {
                val doors=repo.doors()
                state=state.copy(doors=doors,busy=false)
                if(doors.isNotEmpty()) selectDoor(doors.firstOrNull { it.id==state.door?.id } ?: doors.first())
            } catch(e:Exception) { failure(e) }
        }
    }

    fun selectDoor(door: Door) {
        if(state.demo) return
        cameraJob?.cancel(); dayJob?.cancel()
        state=state.copy(door=door,camera=null,url=null,events=emptyList(),ranges=emptyList(),busy=true,
            live=true,error=null,eventError=null,archiveError=null,playing=true)
        cameraJob=viewModelScope.launch {
            try {
                if(door.hasVideo) {
                    val camera=repo.camera(door)
                    ensureActive()
                    state=state.copy(camera=camera,url=camera.liveUrl.takeIf { camera.canLive },busy=false,
                        streamRequest=null,date=LocalDate.now(CameraZone))
                } else state=state.copy(busy=false,notice="У этого входа нет камеры.")
                loadDay(state.date)
            } catch(e:Exception) { failure(e); if(e !is CancellationException && state.signedIn) loadDay(state.date) }
        }
    }

    fun changeDate(date: LocalDate) {
        if(date.isAfter(LocalDate.now(CameraZone))) return
        if(state.demo) { loadDemoDay(date); seek(date.atTime(12,0).atZone(CameraZone).toEpochSecond().coerceAtMost(state.now));return }
        loadDay(date,seekAfter=true)
    }

    fun seekMoment(epoch:Long) {
        val target=epoch.coerceAtMost(state.now)
        val date=Instant.ofEpochSecond(target).atZone(CameraZone).toLocalDate()
        if(date==state.date) seek(target)
        else if(state.demo) { loadDemoDay(date);seek(target) }
        else loadDay(date,seekAfter=true,targetEpoch=target)
    }

    private fun loadDay(date: LocalDate,seekAfter:Boolean=false,targetEpoch:Long?=null) {
        dayJob?.cancel()
        val door=state.door ?: return
        val camera=state.camera
        state=state.copy(date=date,events=emptyList(),ranges=emptyList(),loadingDay=true,eventError=null,archiveError=null)
        dayJob=viewModelScope.launch {
            val from=date.atStartOfDay(CameraZone).toInstant()
            val to=date.plusDays(1).atStartOfDay(CameraZone).toInstant()
            supervisorScope {
                val eventsTask=async { runCatching { repo.events(door,date,date).filter { !it.at.isBefore(from) && it.at.isBefore(to) } } }
                val rangesTask=async { runCatching {
                    if(camera?.canArchive==true) repo.recordings(camera,from,minOf(to,Instant.now())) else emptyList()
                } }
                val events=eventsTask.await(); val ranges=rangesTask.await()
                ensureActive()
                val expired=listOfNotNull(events.exceptionOrNull(),ranges.exceptionOrNull()).filterIsInstance<ApiException>().firstOrNull { it.sessionExpired }
                if(expired!=null) { failure(expired);return@supervisorScope }
                state=state.copy(events=events.getOrDefault(emptyList()).sortedByDescending { it.at },ranges=ranges.getOrDefault(emptyList()),
                    loadingDay=false,eventError=events.exceptionOrNull()?.let(::friendly),
                    archiveError=ranges.exceptionOrNull()?.let(::friendly) ?: camera?.takeIf { !it.canArchive }?.archiveReason?.ifBlank { "Нет доступа к архиву" })
                if(seekAfter) {
                    val target=targetEpoch ?: date.atTime(12,0).atZone(CameraZone).toEpochSecond().coerceAtMost(state.now)
                    val nearest=state.ranges.firstOrNull { target in it.start.epochSecond until it.end.epochSecond }?.let { target }
                        ?: state.ranges.lastOrNull()?.start?.epochSecond ?: target
                    seek(if(targetEpoch!=null) target else nearest, refreshMissing=false)
                }
            }
        }
    }

    fun seek(epoch:Long, refreshMissing:Boolean=true) {
        if(state.loadingDay) return
        val target=epoch.coerceAtMost(state.now)
        val day=Instant.ofEpochSecond(target).atZone(CameraZone).toLocalDate()
        if(day!=state.date) { seekMoment(target);return }
        val range=state.ranges.firstOrNull { target >= it.start.epochSecond && target < it.end.epochSecond }
        if(range==null && refreshMissing && !state.demo && state.camera?.canArchive==true &&
            day==LocalDate.now(CameraZone) && state.ranges.lastOrNull()?.end?.epochSecond?.let { target>it }==true) {
            loadDay(day,seekAfter=true,targetEpoch=target);return
        }
        if(range==null) {
            state=state.copy(selected=target,live=false,url=null,streamRequest=null,playing=false,
                notice=state.archiveError ?: "В это время нет записи. Выберите цветной участок шкалы.")
            return
        }
        val end=minOf(range.end,Instant.ofEpochSecond(target+3600),Instant.now())
        val url=try { if(state.demo) null else state.camera?.let { archivePlaybackUrl(it,Instant.ofEpochSecond(target),end) } }
        catch(_:IllegalArgumentException) { state=state.copy(error="Не удалось открыть ссылку на архив. Обновите камеру.");return }
        state=state.copy(selected=target,live=false,url=url,streamRequest=target,playing=true,
            playerGeneration=state.playerGeneration+1,error=null,notice=null)
    }

    fun openEvent(event:DoorEvent) {
        val containing=state.ranges.firstOrNull { !event.at.isBefore(it.start) && event.at.isBefore(it.end) }
        val target=if(containing!=null) maxOf(event.at.epochSecond-5,containing.start.epochSecond) else event.at.epochSecond
        seek(target)
        if(containing!=null) {
            val lead=event.at.epochSecond-target
            state=state.copy(notice=if(lead>0) "Запись за $lead с до события" else "Начало записи события")
        }
    }

    fun step(seconds:Long) { seek((if(state.live) state.now else state.selected)+seconds) }
    fun goLive() {
        state=state.copy(live=true,selected=state.now,url=state.camera?.liveUrl?.takeIf { state.camera?.canLive==true },
            streamRequest=null,playing=true,speed=1f,error=null,notice=null,playerGeneration=state.playerGeneration+1)
        if(state.date!=LocalDate.now(CameraZone)) {
            if(state.demo) loadDemoDay(LocalDate.now(CameraZone)) else loadDay(LocalDate.now(CameraZone))
        }
    }
    fun playbackPosition(epoch:Long) { if(!state.live) state=state.copy(selected=epoch.coerceAtMost(state.now)) }
    fun savePlaybackPosition(generation:Int,url:String,epoch:Long) {
        if(!state.live && state.playerGeneration==generation && state.url==url)
            state=state.copy(selected=epoch.coerceAtMost(state.now),streamRequest=epoch.coerceAtMost(state.now))
    }
    fun playing(value:Boolean) { state=state.copy(playing=value) }
    fun mute() { state=state.copy(muted=!state.muted) }
    fun speed() { if(!state.live) state=state.copy(speed=when(state.speed) { 1f->2f;2f->4f;else->1f }) }
    fun filter(value:String) { state=state.copy(filter=value) }
    fun streamError(message:String) { state=state.copy(error=message) }
    fun refreshStream() {
        val door=state.door ?: return
        if(state.demo || state.busy) return
        val selected=state.selected; val wasLive=state.live
        state=state.copy(busy=true,error=null)
        cameraJob?.cancel()
        cameraJob=viewModelScope.launch {
            try {
                val camera=repo.camera(door)
                ensureActive()
                state=state.copy(camera=camera,busy=false)
                if(wasLive) goLive() else seek(selected)
            } catch(e:Exception) { failure(e) }
        }
    }

    fun openDoor() {
        val door=state.door ?: return
        if(state.opening || state.now<state.openCooldownUntil || !door.canOpen) return
        if(state.demo) { state=state.copy(notice="Демо: команда открытия не отправляется.");return }
        state=state.copy(opening=true,error=null,notice=null)
        openJob=viewModelScope.launch {
            try {
                repo.openDoor(door)
                state=state.copy(opening=false,openCooldownUntil=Instant.now().epochSecond+5,notice="Команда открытия принята")
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                state=state.copy(opening=false,openCooldownUntil=Instant.now().epochSecond+5)
                failure(e)
            }
        }
    }

    fun logout() {
        cameraJob?.cancel();dayJob?.cancel();authJob?.cancel();openJob?.cancel()
        cancelExport()
        exportEnqueueJob?.cancel()
        exportObserver?.cancel()
        exportPrefs.edit().remove("workId").apply()
        export=ExportState()
        repo.logout();state=DoorState()
    }

    fun startExport(uri:Uri,from:Long,to:Long,doorId:String) {
        if(export.active) {state=state.copy(error="Дождитесь завершения текущего сохранения");return}
        val session=repo.currentSession() ?: return
        if(from>=to || to>Instant.now().epochSecond) {state=state.copy(error="Проверьте выбранный диапазон");return}
        val request=OneTimeWorkRequestBuilder<VideoExportWorker>()
            .setInputData(workDataOf("uri" to uri.toString(),"doorId" to doorId,"userId" to session.userId,"from" to from,"to" to to))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        export=ExportState("queued",message="Подготавливаем сохранение…")
        exportEnqueueJob=viewModelScope.launch {
            try {
            val active=withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(VideoExportWorker.UNIQUE_NAME).get().firstOrNull { !it.state.isFinished }
            }
            if(repo.currentSession()?.token!=session.token) return@launch
            if(active!=null) {
                exportPrefs.edit().putString("workId",active.id.toString()).apply()
                observeExport(active.id)
                state=state.copy(notice="Предыдущее сохранение ещё продолжается")
                withContext(Dispatchers.IO) {runCatching {DocumentsContract.deleteDocument(getApplication<Application>().contentResolver,uri)}}
            } else {
                exportPrefs.edit().putString("workId",request.id.toString()).apply()
                // Only one export can run. REPLACE also guarantees the observed request is accepted.
                workManager.enqueueUniqueWork(VideoExportWorker.UNIQUE_NAME,ExistingWorkPolicy.REPLACE,request)
                observeExport(request.id)
            }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {export=ExportState("error",message="Не удалось начать сохранение. Повторите попытку.")}
        }
    }
    private fun observeExport(id:UUID) {
        exportObserver?.cancel()
        exportObserver=viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if(info!=null) export=when(info.state) {
                    WorkInfo.State.ENQUEUED,WorkInfo.State.BLOCKED -> ExportState("queued",message="Ожидаем подключение и начало загрузки…")
                    WorkInfo.State.RUNNING -> ExportState("running",info.progress.getInt("percent",0),info.progress.getString("message") ?: "Сохраняем видео…")
                    WorkInfo.State.SUCCEEDED -> ExportState("done",100,info.outputData.getString("message").orEmpty(),info.outputData.getString("uri"))
                    WorkInfo.State.FAILED -> ExportState("error",message=info.outputData.getString("message") ?: "Не удалось сохранить видео. Повторите загрузку.")
                    WorkInfo.State.CANCELLED -> ExportState("cancelled",message="Сохранение отменено")
                }
            }
        }
    }
    fun cancelExport() {workManager.cancelUniqueWork(VideoExportWorker.UNIQUE_NAME)}
    fun dismissExport() {
        if(export.active) return
        exportObserver?.cancel();exportPrefs.edit().remove("workId").apply();export=ExportState()
    }
    fun demo() {
        authJob?.cancel();cameraJob?.cancel();dayJob?.cancel();openJob?.cancel()
        val door=Door("demo","demo","demo","Главный вход","Демонстрационный дом · подъезд 1",true,true)
        state=DoorState(signedIn=true,demo=true,doors=listOf(door),door=door)
        loadDemoDay(state.date)
    }
    private fun loadDemoDay(date:LocalDate) {
        val start=date.atStartOfDay(CameraZone).toInstant()
        val end=minOf(date.plusDays(1).atStartOfDay(CameraZone).toInstant(),Instant.now())
        val range=RecordingRange(start,end)
        val events=listOf(0,1,2,3,4,5,6,7).map { index ->
            val type= listOf("HANDSET_CALL","OPEN_API","OPEN_HANDSET")[index%3]
            DoorEvent("demo-$index",end.minusSeconds((index*987+240).toLong()),type,
                when(type) {"HANDSET_CALL"->"Звонок в домофон";"OPEN_API"->"Открыто из приложения";else->"Открыто с трубки"},"demo",null)
        }.filter { !it.at.isBefore(start) }
        state=state.copy(date=date,events=events,ranges=listOf(range),eventError=null,archiveError=null)
    }
    private fun failure(e:Exception) {
        if(e is CancellationException) throw e
        if(e is ApiException && e.sessionExpired) { logout();state=state.copy(error="Сессия истекла. Войдите ещё раз.") }
        else state=state.copy(busy=false,loadingDay=false,error=friendly(e))
    }
    private fun friendly(e:Throwable):String = if(e is ApiException) e.message ?: "Не удалось выполнить запрос" else "Не удалось подключиться. Проверьте интернет и повторите."
}
