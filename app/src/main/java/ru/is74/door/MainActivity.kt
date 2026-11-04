package ru.is74.door

import android.app.DatePickerDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.is74.door.data.DoorEvent
import ru.is74.door.ui.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            DoorTheme {
                val vm: DoorViewModel = viewModel()
                Surface(Modifier.fillMaxSize(),color=Ink) {
                    if(vm.state.signedIn) CameraScreen(vm) else LoginScreen(vm)
                }
            }
        }
    }
}

private val timeFormat=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(CameraZone)
private val dayFormat=DateTimeFormatter.ofPattern("d MMMM",Locale.forLanguageTag("ru"))

@Composable
private fun LoginScreen(vm:DoorViewModel) {
    val s=vm.state
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(72.dp).background(Brush.radialGradient(listOf(Color(0xFF244B46),Ink)),CircleShape),contentAlignment=Alignment.Center) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp,Mint.copy(alpha=0.25f),RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center) {
                Glyph("door",Mint,Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Домофон 74",style=MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(10.dp))
        Text("Ваш вход. Всё под рукой.",color=Muted,style=MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Surface(shape=RoundedCornerShape(24.dp),color=Panel,modifier=Modifier.widthIn(max=460.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(when(s.authStep) {0->"Войти в Интерсвязь";1->"Подтвердите номер";else->"Выберите адрес"},style=MaterialTheme.typography.titleLarge)
                when(s.authStep) {
                    0 -> {
                        Text("Используйте номер, привязанный к вашему личному кабинету.",color=Muted)
                        OutlinedTextField(value=s.phone,onValueChange=vm::phone,label={Text("Номер телефона")},
                            placeholder={Text("900 000-00-00")},prefix={Text("+7 ")},singleLine=true,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),
                            modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),enabled=!s.busy)
                        Button(onClick=vm::requestCode,enabled=!s.busy && s.now>=s.resendAt,modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(16.dp)) {
                            if(s.busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=Ink)
                            else Text(if(s.now<s.resendAt) "Повторить через ${s.resendAt-s.now} с" else "Получить код")
                        }
                    }
                    1 -> {
                        val message=remember(s.challenge) { HtmlCompat.fromHtml(s.challenge?.message.orEmpty(),HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
                        Text(message.ifBlank { "Введите код подтверждения. Он может прийти звонком или сообщением." },color=Muted)
                        OutlinedTextField(value=s.code,onValueChange=vm::code,label={Text("Код подтверждения")},singleLine=true,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth(),enabled=!s.busy,shape=RoundedCornerShape(14.dp))
                        Button(onClick=vm::confirmCode,enabled=!s.busy,modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(16.dp)) {
                            if(s.busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=Ink) else Text("Продолжить")
                        }
                        TextButton(onClick=vm::requestCode,enabled=!s.busy && s.now>=s.resendAt) {
                            Text(if(s.now<s.resendAt) "Новый код через ${s.resendAt-s.now} с" else "Запросить новый код")
                        }
                        TextButton(onClick=vm::backToPhone,enabled=!s.busy) { Text("Изменить номер",color=Muted) }
                    }
                    else -> {
                        Text("К этому номеру привязаны договоры:",color=Muted)
                        s.confirmed?.accounts?.forEach { account ->
                            OutlinedButton(onClick={vm.signIn(account)},enabled=!s.busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)) {
                                Text(account.address,modifier=Modifier.padding(vertical=8.dp))
                            }
                        }
                        if(s.confirmed?.accounts.isNullOrEmpty()) Text("Не найдено доступных договоров.",color=Amber)
                        if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick=vm::backToPhone,enabled=!s.busy) { Text("Вернуться ко входу") }
                    }
                }
                s.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick=vm::demo,enabled=!s.busy) { Text("Посмотреть демо");Spacer(Modifier.width(8.dp));Glyph("chevron",Mint,Modifier.size(16.dp)) }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Text("Неофициальный клиент Интерсвязи\nКамера · архив · события",color=Muted.copy(alpha=0.7f),fontSize=12.sp,textAlign=TextAlign.Center,lineHeight=18.sp)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CameraScreen(vm:DoorViewModel) {
    val s=vm.state
    var menu by remember { mutableStateOf(false) }
    var doorMenu by remember { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var openDialog by rememberSaveable { mutableStateOf(false) }
    var logoutDialog by rememberSaveable { mutableStateOf(false) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var exportFrom by rememberSaveable { mutableLongStateOf(0) }
    var exportTo by rememberSaveable { mutableLongStateOf(0) }
    var exportDoorId by rememberSaveable { mutableStateOf("") }
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    val videoHeight=((minOf(configuration.screenWidthDp,800)-40)*9f/16f).coerceAtMost(configuration.screenHeightDp*0.42f).dp
    val saveVideo=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if(uri!=null && exportFrom<exportTo && exportDoorId.isNotBlank()) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.startExport(uri,exportFrom,exportTo,exportDoorId)
        }
    }
    fun saveName():String {
        val fmt=DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(CameraZone)
        return "Domofon74_${fmt.format(Instant.ofEpochSecond(exportFrom))}_${fmt.format(Instant.ofEpochSecond(exportTo))}.mp4"
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { saveVideo.launch(saveName()) }
    fun openExportedFile(share:Boolean) {
        val uri=vm.export.uri?.let(Uri::parse) ?: return
        val intent=if(share) Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,uri)
            else Intent(context,SavedVideoActivity::class.java).setData(uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {context.startActivity(if(share) Intent.createChooser(intent,"Поделиться видео") else intent)}
            .onFailure {vm.streamError("Не удалось открыть файл. Он сохранён в выбранной папке.")}
    }
    val events=s.events.filter { when(s.filter) { "calls"->it.type.contains("CALL");"opens"->it.type.startsWith("OPEN");else->true } }
    val dayStart=s.date.atStartOfDay(CameraZone).toEpochSecond()
    val dayEnd=s.date.plusDays(1).atStartOfDay(CameraZone).toEpochSecond()
    fun chooseDay() {
        DatePickerDialog(context,{_,y,m,d->vm.changeDate(LocalDate.of(y,m+1,d))},s.date.year,s.date.monthValue-1,s.date.dayOfMonth).apply {
            datePicker.maxDate=LocalDate.now(CameraZone).atTime(23,59,59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            s.camera?.archiveStart?.let {
                datePicker.minDate=it.atZone(CameraZone).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.show()
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=800.dp).fillMaxWidth(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ДОМОФОН 74",color=Mint,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
                        Text("Мой дом",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(top=4.dp))
                    }
                    Box {
                        IconButton(onClick={menu=true},modifier=Modifier.background(Panel,CircleShape).semantics { contentDescription="Меню аккаунта" }) { Glyph("door",Mint) }
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("Обновить домофоны")},onClick={menu=false;vm.refreshDoors()})
                            DropdownMenuItem(text={Text(if(s.demo) "Выйти из демо" else "Выйти из аккаунта")},onClick={menu=false;logoutDialog=true})
                        }
                    }
                }
            }
            if(s.demo) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF263146)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("ДЕМО",color=Blue,fontSize=10.sp,fontWeight=FontWeight.Bold)
                    Text("  Пример событий, без подключения к дому",color=Blue,fontSize=12.sp,modifier=Modifier.weight(1f))
                }
            }
            item {
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled=s.doors.size>1) {doorMenu=true}.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.door?.title ?: "Ваши домофоны",fontWeight=FontWeight.SemiBold,fontSize=18.sp)
                            Text(s.door?.address ?: "Выберите доступный вход",color=Muted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                        }
                        if(s.doors.size>1) Glyph("down",Mint)
                    }
                    DropdownMenu(expanded=doorMenu,onDismissRequest={doorMenu=false}) {
                        s.doors.forEach { door -> DropdownMenuItem(text={Column {Text(door.title);Text(door.address,fontSize=12.sp,color=Muted)}},onClick={doorMenu=false;vm.selectDoor(door)}) }
                    }
                }
            }
            if(s.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(),color=Mint) }
            if(!s.busy && s.doors.isEmpty()) item {
                Surface(shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Нет доступных домофонов",style=MaterialTheme.typography.titleMedium)
                    Text("Проверьте выбранный договор и доступ к дому в Интерсвязи.",color=Muted)
                    TextButton(onClick=vm::refreshDoors) { Text("Обновить") }
                } }
            }
            if(s.door!=null) stickyHeader(key="camera-player") {
                Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Panel)) {
                    Box(Modifier.fillMaxWidth().height(videoHeight).background(Color.Black)) {
                        key(s.playerGeneration) {
                            val generation=s.playerGeneration
                            CameraPlayer(url=s.url,isLive=s.live,playing=s.playing,muted=s.muted,speed=s.speed,
                                onPosition={},onPlayingChange=vm::playing,onError=vm::streamError,modifier=Modifier.fillMaxSize(),demo=s.demo,
                                headers=vm.headers,requestedEpochSeconds=s.streamRequest,onAbsolutePosition=vm::playbackPosition,onRefreshRequested=vm::refreshStream,
                                onReleasedPosition={url,epoch->vm.savePlaybackPosition(generation,url,epoch)})
                        }
                        Row(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(7.dp)).background(Ink.copy(alpha=0.82f)).padding(horizontal=9.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if(s.live) Mint else Blue,CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(if(s.demo) "ПРЕДПРОСМОТР" else if(s.live) "ПРЯМОЙ ЭФИР" else "АРХИВ",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=0.6.sp)
                        }
                        if(!s.live) Text(timeFormat.format(Instant.ofEpochSecond(s.selected)),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=Color.White,
                            modifier=Modifier.align(Alignment.TopEnd).padding(12.dp).background(Ink.copy(alpha=0.82f),RoundedCornerShape(7.dp)).padding(horizontal=8.dp,vertical=5.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        IconButton(onClick={vm.playing(!s.playing)},enabled=s.url!=null,modifier=Modifier.semantics {contentDescription=if(s.playing) "Пауза" else "Воспроизвести"}) { Glyph(if(s.playing) "pause" else "play",if(s.url!=null) Color.White else Muted) }
                        TextButton(onClick={vm.step(-10)},enabled=!s.loadingDay) { Text("−10 с",color=Color.White,fontSize=13.sp) }
                        TextButton(onClick={vm.step(10)},enabled=!s.live && !s.loadingDay) { Text("+10 с",fontSize=13.sp) }
                        TextButton(onClick=vm::speed,enabled=!s.live) { Text("${s.speed.toInt()}×",fontSize=13.sp) }
                        IconButton(onClick=vm::mute,modifier=Modifier.semantics {contentDescription=if(s.muted) "Включить звук" else "Выключить звук"}) { Glyph(if(s.muted) "muted" else "sound",Color.White) }
                    }
                }
            }
            s.error?.let { message -> item { MessageCard(message,true,onDismiss=vm::clearMessage,onRetry=if(s.door!=null) vm::refreshStream else vm::refreshDoors) } }
            s.notice?.let { message -> item { MessageCard(message,false,onDismiss=vm::clearMessage) } }
            if(s.door!=null) item {
                val cooldown=(s.openCooldownUntil-s.now).coerceAtLeast(0)
                Button(onClick={openDialog=true},enabled=s.door.canOpen && !s.opening && cooldown==0L,
                    modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(17.dp)) {
                    if(s.opening) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=Ink) else Glyph("door",Ink,Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(when { s.opening->"Открываем…";cooldown>0->"Повторить через $cooldown с";!s.door.canOpen->"Вход недоступен";else->"Открыть дверь" },fontSize=15.sp)
                }
            }
            if(s.door!=null) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(top=18.dp,bottom=8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Архив",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        TextButton(onClick=vm::goLive,contentPadding=PaddingValues(horizontal=10.dp),colors=ButtonDefaults.textButtonColors(containerColor=if(s.live) Mint.copy(alpha=0.12f) else Color.Transparent)) {
                            Box(Modifier.size(6.dp).background(Mint,CircleShape));Spacer(Modifier.width(7.dp));Text("В эфир",fontSize=12.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={vm.changeDate(s.date.minusDays(1))},enabled=s.camera?.archiveStart?.let { s.date.isAfter(it.atZone(CameraZone).toLocalDate()) } ?: true) { Text("‹",fontSize=26.sp) }
                        TextButton(onClick={chooseDay()},modifier=Modifier.weight(1f),contentPadding=PaddingValues(0.dp)) {
                            Text((if(s.date==LocalDate.now(CameraZone)) "Сегодня, " else "")+s.date.format(dayFormat),color=Color.White,fontSize=13.sp,maxLines=1)
                            Spacer(Modifier.width(5.dp));Glyph("down",Muted,Modifier.size(13.dp))
                        }
                        TextButton(onClick={vm.changeDate(s.date.plusDays(1))},enabled=s.date<LocalDate.now(CameraZone)) { Text("›",fontSize=26.sp) }
                    }
                    if(s.loadingDay) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    CameraTimeline(selectedEpochSeconds=s.selected.coerceIn(dayStart,minOf(dayEnd-1,s.now).coerceAtLeast(dayStart)),
                        dayStartEpochSeconds=dayStart,dayEndEpochSeconds=dayEnd,
                        events=s.events.map { TimelineMarker(it.at.epochSecond,it.type,it.title) },
                        ranges=s.ranges.map { TimelineRange(it.start.epochSecond,it.end.epochSecond) },onSeek=vm::seek,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(onClick={picker=true},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),shape=RoundedCornerShape(12.dp),enabled=!s.loadingDay) {
                        Glyph("clock",Mint,Modifier.size(17.dp));Spacer(Modifier.width(8.dp));Text("Перейти к точному времени",fontSize=12.sp)
                    }
                    TextButton(onClick={exportDialog=true},enabled=!s.demo && s.camera?.canArchive==true && !vm.export.active,
                        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                        Glyph("download",Mint,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Скачать фрагмент · MP4",fontSize=13.sp)
                    }
                    Text("Время камеры · Челябинск, UTC+5",color=Muted,fontSize=10.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(8.dp))
                    if(s.archiveError!=null) Text(s.archiveError,color=Amber,fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp))
                    else if(!s.loadingDay && s.ranges.isEmpty()) Text("За этот день нет доступной записи",color=Muted,fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp))
                }
            }
            if(vm.export.phase!="none") item(key="export-progress") {
                Surface(shape=RoundedCornerShape(18.dp),color=Panel) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Glyph("download",Mint,Modifier.size(20.dp));Spacer(Modifier.width(9.dp))
                            Text(when(vm.export.phase) {"done"->"Видео сохранено";"error"->"Не удалось сохранить";"cancelled"->"Загрузка отменена";else->"Сохранение видео"},fontWeight=FontWeight.SemiBold)
                        }
                        Text(vm.export.message,color=if(vm.export.phase=="error") Amber else Muted,fontSize=12.sp)
                        if(vm.export.active) {
                            LinearProgressIndicator(progress={vm.export.percent/100f},modifier=Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("${vm.export.percent}%",color=Mint,modifier=Modifier.weight(1f))
                                TextButton(onClick=vm::cancelExport) {Text("Отменить")}
                            }
                        } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(vm.export.phase=="done") {
                                TextButton(onClick={openExportedFile(false)}) {Text("Открыть")}
                                TextButton(onClick={openExportedFile(true)}) {Text("Поделиться")}
                            }
                            TextButton(onClick=vm::dismissExport) {Text("Скрыть",color=Muted)}
                        }
                    }
                }
            }
            if(s.door!=null) item {
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("События",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        Text("${s.events.size} за день",color=Muted,fontSize=12.sp)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("all" to "Все","calls" to "Звонки","opens" to "Открытия").forEach { (id,label) ->
                            FilterChip(selected=s.filter==id,onClick={vm.filter(id)},label={Text(label)},shape=RoundedCornerShape(10.dp))
                        }
                    }
                    if(s.eventError!=null) MessageCard(s.eventError,true,onDismiss={},onRetry={vm.changeDate(s.date)})
                    else if(!s.loadingDay && events.isEmpty()) Text(if(s.events.isEmpty()) "За этот день событий нет" else "Нет событий этого типа",color=Muted,modifier=Modifier.padding(vertical=12.dp))
                }
            }
            items(events,key={it.id}) { event -> EventRow(event,available=s.ranges.any { !event.at.isBefore(it.start) && event.at.isBefore(it.end) },onClick={vm.openEvent(event)}) }
            if(events.isNotEmpty()) item { Text("Нажмите на событие, чтобы увидеть запись\nс небольшим запасом времени до него.",color=Muted,fontSize=11.sp,lineHeight=17.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=4.dp)) }
        }
    }
    if(picker) MomentPicker(initialEpochSeconds=s.selected,minEpochSeconds=s.camera?.archiveStart?.epochSecond ?: s.now-7*86400,
        maxEpochSeconds=s.now,onDismiss={picker=false},onConfirm={epoch->picker=false;val date=Instant.ofEpochSecond(epoch).atZone(CameraZone).toLocalDate();if(date==s.date) vm.seek(epoch) else vm.seekMoment(epoch)})
    if(exportDialog) ExportRangeDialog(initialEpochSeconds=s.selected,minEpochSeconds=s.camera?.archiveStart?.epochSecond ?: s.now-7*86400,
        maxEpochSeconds=s.now,onDismiss={exportDialog=false},onExport={from,to->
            exportDialog=false;exportFrom=from;exportTo=to;exportDoorId=s.door?.id.orEmpty()
            if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else saveVideo.launch(saveName())
        })
    if(openDialog) AlertDialog(onDismissRequest={openDialog=false},icon={Glyph("door",Mint,Modifier.size(30.dp))},
        title={Text(if(s.demo) "Открытие в демо" else "Открыть дверь?")},
        text={Text(if(s.demo) "Это демонстрация. Команда домофону не отправится." else "${s.door?.title}\n${s.door?.address}")},
        confirmButton={Button(onClick={openDialog=false;vm.openDoor()}) {Text("Открыть")}},dismissButton={TextButton(onClick={openDialog=false}) {Text("Отмена")}})
    if(logoutDialog) AlertDialog(onDismissRequest={logoutDialog=false},title={Text(if(s.demo) "Выйти из демо?" else "Выйти из аккаунта?")},
        text={Text(if(s.demo) "Вы вернётесь на экран входа." else "Для следующего входа потребуется новый код подтверждения.")},
        confirmButton={TextButton(onClick={logoutDialog=false;vm.logout()}) {Text("Выйти")}},dismissButton={TextButton(onClick={logoutDialog=false}) {Text("Отмена")}})
}

@Composable
private fun EventRow(event:DoorEvent,available:Boolean,onClick:()->Unit) {
    val call=event.type.contains("CALL")
    val accent=if(call) Amber else if(event.type.startsWith("OPEN")) Color(0xFFB8A3FF) else Blue
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable(onClick=onClick).padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(accent.copy(alpha=0.1f),RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center) { Glyph(if(call) "call" else "door",accent,Modifier.size(21.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(event.title,fontWeight=FontWeight.Medium,fontSize=13.sp)
            Text(if(available) "Смотреть момент" else "Запись недоступна",color=if(available) Muted else Amber,fontSize=11.sp)
        }
        Text(timeFormat.format(event.at),fontSize=12.sp,fontFamily=FontFamily.Monospace,color=Muted)
        Spacer(Modifier.width(7.dp));Glyph("chevron",Muted,Modifier.size(15.dp))
    }
}

@Composable
private fun MessageCard(message:String,error:Boolean,onDismiss:()->Unit,onRetry:(()->Unit)?=null) {
    Surface(shape=RoundedCornerShape(14.dp),color=if(error) Color(0xFF392A30) else Color(0xFF17352F)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(message,color=if(error) Color(0xFFFFB9B5) else Mint,fontSize=13.sp)
            Row {
                if(onRetry!=null) TextButton(onClick=onRetry,contentPadding=PaddingValues(end=16.dp)) {Text("Повторить")}
                TextButton(onClick=onDismiss,contentPadding=PaddingValues(0.dp)) {Text("Понятно",color=Muted)}
            }
        }
    }
}
