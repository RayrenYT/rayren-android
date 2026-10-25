@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.rayren.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.rayren.android.*
import com.rayren.domain.*
import com.rayren.domain.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Mint=Color(0xFF9BE7C3)
private val Ink=Color(0xFF0C1114)
private val Panel=Color(0xFF171F24)
private val Muted=Color(0xFF99A9B2)
private val Amber=Color(0xFFFFCD8A)
private val scheme=darkColorScheme(primary=Mint,onPrimary=Color(0xFF0C3626),background=Ink,surface=Ink,surfaceContainer=Panel,surfaceVariant=Panel,onSurface=Color(0xFFE8EFF0),onSurfaceVariant=Muted,secondary=Color(0xFFACCEFA),outline=Color(0xFF354249),error=Color(0xFFFFB4AB))

@Composable fun RayrenScreen(controller:AppController,importDraft:String?,setImport:(String?)->Unit,onConnect:()->Unit,onScan:()->Unit,onExport:()->Unit,onSystemVpn:()->Unit){
    val state by controller.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable{mutableIntStateOf(0)}
    val snack=remember{SnackbarHostState()}
    MaterialTheme(colorScheme=scheme){
        LaunchedEffect(state.message){if(state.message.isNotEmpty()){snack.showSnackbar(state.message);controller.clearMessage()}}
        Scaffold(snackbarHost={SnackbarHost(snack)},bottomBar={
            NavigationBar(containerColor=Ink,tonalElevation=0.dp){
                listOf("Главная" to Icons.Outlined.Shield,"Серверы" to Icons.Outlined.Dns,"Настройки" to Icons.Outlined.Tune,"Журнал" to Icons.AutoMirrored.Outlined.Article).forEachIndexed{i,(name,icon)->
                    NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,null)},label={Text(name)},colors=NavigationBarItemDefaults.colors(indicatorColor=Panel))
                }
            }
        }){padding->
            Box(Modifier.fillMaxSize().padding(padding)){
                if(!state.loaded)CircularProgressIndicator(Modifier.align(Alignment.Center))
                else when(tab){
                    0->Home(state,onConnect,{setImport("")},{tab=1},controller::chooseMode)
                    1->Servers(state,controller,{setImport("")},onScan)
                    2->SettingsPage(state,controller,onSystemVpn)
                    3->Diagnostics(state,controller,onExport)
                }
            }
        }
        if(importDraft!=null)ImportDialog(importDraft,{setImport(null)},{text,key->controller.import(text,key);setImport(null)},onScan)
    }
}

@Composable private fun Heading(title:String,sub:String="",action:(@Composable ()->Unit)?=null){
    Row(Modifier.fillMaxWidth().padding(bottom=20.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text(title,fontSize=28.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-0.6).sp);if(sub.isNotBlank())Text(sub,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=5.dp))}
        action?.invoke()
    }
}
@Composable private fun Section(title:String,content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(title,color=Muted,fontSize=12.sp,fontWeight=FontWeight.Medium,letterSpacing=1.sp)
        content()
    }
}
@Composable private fun Home(state:AppState,connect:()->Unit,import:()->Unit,servers:()->Unit,setMode:(Mode)->Unit){
    val c=state.connection;val p=state.data.preferences;val active=state.data.profiles.find{it.id==c.profileId}
    val connected=c.phase==Phase.CONNECTED;val running=c.phase!=Phase.IDLE
    var explanation by remember{mutableStateOf(false)}
    var time by remember{mutableLongStateOf(System.currentTimeMillis())}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connected,c.started,lifecycle){lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(connected){time=System.currentTimeMillis();delay(1000)}}}
    val colour=when(c.phase){Phase.CONNECTED->Mint;Phase.BLOCKED->Amber;else->Muted}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=18.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            Text("RAYREN",fontSize=22.sp,fontWeight=FontWeight.Bold,letterSpacing=4.sp)
            Text("0.1 · ALPHA",color=Muted,fontSize=10.sp,letterSpacing=1.sp,modifier=Modifier.border(1.dp,scheme.outline,RoundedCornerShape(20.dp)).padding(horizontal=10.dp,vertical=6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
            Text(when(c.phase){Phase.IDLE->"ГОТОВ К ПОДКЛЮЧЕНИЮ";Phase.CONNECTED->"МАРШРУТ ПРОВЕРЕН";Phase.BLOCKED->"ТРАФИК ПРИОСТАНОВЛЕН";Phase.RECONNECTING->"ВОССТАНОВЛЕНИЕ";Phase.STOPPING->"ОТКЛЮЧЕНИЕ";else->"ПОДКЛЮЧЕНИЕ"},fontSize=11.sp,color=colour,letterSpacing=1.2.sp)
        }
        Box(Modifier.size(180.dp).background(Brush.radialGradient(listOf((if(connected)Mint else Color(0xFF638A99)).copy(alpha=.15f),Ink)),CircleShape).border(1.dp,colour.copy(alpha=.25f),CircleShape).padding(16.dp),contentAlignment=Alignment.Center){
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Panel).clickable(enabled=state.loaded&&!state.fatal&&c.phase!=Phase.STOPPING,onClick=connect),contentAlignment=Alignment.Center){
                Icon(if(connected)Icons.Outlined.VerifiedUser else Icons.Outlined.PowerSettingsNew,if(running)"Отключить VPN" else "Подключить VPN",Modifier.size(64.dp),tint=colour)
            }
            if(c.phase in setOf(Phase.CONNECTING,Phase.RECONNECTING,Phase.STOPPING))CircularProgressIndicator(Modifier.fillMaxSize(),strokeWidth=2.dp)
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(if(running)c.serverName.ifBlank{active?.name ?: "Выбираем маршрут"} else if(state.data.profiles.isEmpty())"Ваш интернет. Ваш маршрут." else modeName(p.mode),fontSize=25.sp,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(if(running)"${active?.let{engineName(it.engine)+" · "+it.protocol} ?: "Rayren"}  ·  ${c.network}" else "Xray · OpenFlux · olcRTC",color=Muted,fontSize=13.sp,textAlign=TextAlign.Center)
            if(c.phase==Phase.BLOCKED)Text(c.detail,color=Amber,fontSize=12.sp,textAlign=TextAlign.Center)
        }
        if(state.data.profiles.isEmpty()){
            OutlinedButton(onClick=import){Icon(Icons.Outlined.Add,null);Text("Добавить подписку")}
            Text("Добавьте ссылку или QR-код. Rayren проверит доступные серверы и выберет соединение.",color=Muted,textAlign=TextAlign.Center,fontSize=14.sp)
        }else{
            Section("ТЕКУЩИЙ МАРШРУТ"){
                Row(Modifier.fillMaxWidth().clickable{if(running)explanation=true else servers()},verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text(c.serverName.takeIf{running&&it.isNotBlank()} ?: state.data.profiles.find{p.mode==Mode.MANUAL&&it.id==p.selectedId}?.name ?: regionName(p.region),fontWeight=FontWeight.Medium);Text(modeName(p.mode),color=Muted,fontSize=12.sp);Text(if(c.exitIp.isBlank())"IP выхода появится после подключения" else c.exitIp,color=Muted,fontSize=12.sp)}
                    Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
                }
                HorizontalDivider(color=scheme.outline.copy(alpha=.5f))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Metric("Задержка",c.latency?.let{"$it мс"} ?: "—")
                    Metric("↓ Входящий",if(connected)"${bytes(c.downRate)}/с" else "—")
                    Metric("↑ Исходящий",if(connected)"${bytes(c.upRate)}/с" else "—")
                }
                if(running)Text("Сеанс ${duration(if(c.started>0)(time-c.started)/1000 else 0)}  ·  ↓ ${bytes(c.down)}  ↑ ${bytes(c.up)}",fontSize=11.sp,color=Muted)
                if(running)TextButton(onClick={explanation=true}){Icon(Icons.Outlined.Info,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Почему выбран этот сервер?")}
            }
            ModePicker(p.mode,setMode)

        }
        Text(if(running)"Во время смены маршрута VPN удерживает трафик. Для защиты при закрытии приложения включите постоянный VPN в Android." else "Подключение начинается только после вашего нажатия.",color=Muted.copy(alpha=.8f),fontSize=11.sp,textAlign=TextAlign.Center)
        if(c.privateDns)Text("Android Private DNS включён. При сбоях DNS проверьте совместимость в настройках сети.",color=Amber,fontSize=12.sp)
    }
    if(explanation)AlertDialog(onDismissRequest={explanation=false},title={Text("Почему этот сервер")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(c.serverName.ifBlank{"Маршрут ещё не выбран"},fontWeight=FontWeight.SemiBold)
            val decision=c.decision?.takeIf{connected&&it.selectedRouteId==c.profileId}
            if(decision==null)Text("Недостаточно свежих данных. Дождитесь проверки маршрута.")
            else {
                decision.reasons.forEach{Text(it.text,fontSize=14.sp)}
                decision.latencyMs?.let{Text("HTTPS: $it мс · проверено ${((time-decision.recordedAt).coerceAtLeast(0)/1000)} сек назад",color=Muted,fontSize=12.sp)}
                if(decision.failedAlternatives>0)Text("Других маршрутов с ошибками в этой сети: ${decision.failedAlternatives}",color=Muted,fontSize=12.sp)
            }
        }
    },confirmButton={TextButton(onClick={explanation=false}){Text("Понятно")}},dismissButton={TextButton(onClick={explanation=false;servers()}){Text("Серверы")}})
}
@Composable private fun Metric(title:String,value:String){Column{Text(title,color=Muted,fontSize=10.sp);Text(value,fontSize=16.sp,modifier=Modifier.padding(top=5.dp))}}

@Composable private fun ModePicker(mode:Mode,set:(Mode)->Unit){
    var help by remember{mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Режим подключения",Modifier.weight(1f),fontSize=13.sp,color=Muted);IconButton(onClick={help=true},modifier=Modifier.size(32.dp)){Icon(Icons.Outlined.Info,"О режимах",Modifier.size(18.dp),tint=Muted)}}
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(5.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            Mode.entries.forEach{item->
                val selected=item==mode
                val colour by androidx.compose.animation.animateColorAsState(if(selected)Mint else Panel,label="mode")
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(colour).selectable(selected=selected,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={set(item)}).padding(vertical=13.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Icon(when(item){Mode.AUTO->Icons.Outlined.AutoAwesome;Mode.SMART->Icons.Outlined.Speed;Mode.MANUAL->Icons.Outlined.TouchApp},null,Modifier.size(21.dp),tint=if(selected)Ink else Muted)
                    Text(ModeText.short(item),fontSize=12.sp,fontWeight=FontWeight.Medium,color=if(selected)Ink else Muted)
                }
            }
        }
    }
    if(help)AlertDialog(onDismissRequest={help=false},title={Text("Режимы подключения")},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){Mode.entries.forEach{Text(ModeText.short(it),fontWeight=FontWeight.SemiBold);Text(ModeText.description(it),fontSize=13.sp)}}},confirmButton={TextButton(onClick={help=false}){Text("Понятно")}})
}

private data class ServerRows(val group:ServerGroup,val matches:List<Profile>,val categories:Map<RouteState,List<Profile>>,val visible:List<Profile>)
@Composable private fun Servers(state:AppState,controller:AppController,import:()->Unit,scan:()->Unit){
    val context=LocalContext.current
    var search by rememberSaveable{mutableStateOf("")}
    var deleting by remember{mutableStateOf<Profile?>(null)}
    var collapsed by remember{mutableStateOf(emptySet<String>())}
    var editing by remember{mutableStateOf<ServerGroup?>(null)}
    var deletingSource by remember{mutableStateOf<ServerGroup?>(null)}
    var selectionSource by remember{mutableStateOf<ServerGroup?>(null)}
    var states by remember{mutableStateOf(emptyMap<String,RouteState?>())}
    var help by remember{mutableStateOf(false)}
    val groups=remember(state.data){ServerGroups.list(state.data)}
    val reserveIds=remember(state.data.profiles,state.data.subscriptions){ReserveRoutes.ids(state.data.profiles,state.data.subscriptions)}
    var pages by remember(search,state.data.preferences.sortServersByPing){mutableStateOf(emptyMap<String,Int>())}
    var opened by remember{mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle){lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){controller.serversOpened();opened=true;kotlinx.coroutines.awaitCancellation()}}
    val checkedAt=System.currentTimeMillis()
    val prepared=remember(groups,search,states,state.probes,state.data.preferences.sortServersByPing,checkedAt/30000){groups.map{group->
        val matches=group.profiles.filter{group.name.contains(search,true)||"${it.name} ${it.region} ${it.engine} ${it.protocol}".contains(search,true)}
        val categories=matches.groupBy{RouteEvidence.state(state.probes[it.id],checkedAt)}
        val visible=ServerList.ordered(if(states[group.id]==null)matches else categories[states[group.id]].orEmpty(),state.probes,state.data.preferences.sortServersByPing)
        ServerRows(group,matches,categories,visible)
    }}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=22.dp),contentPadding=PaddingValues(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Heading("Серверы","${state.data.profiles.size} маршрутов · ${state.data.subscriptions.size} подписок"){TextButton(onClick=controller::refreshNow,enabled=!state.busy){Icon(Icons.Outlined.Refresh,null,Modifier.size(18.dp));Text("Обновить все",fontSize=11.sp)}}}
        item{OutlinedButton(onClick={runCatching{context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(PersonalSubscription.BOT_URL)))}.onFailure{controller.message("Откройте @rayrenhelpbot и отправьте /rayren")}},modifier=Modifier.fillMaxWidth()){
            Icon(Icons.Outlined.Send,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Моя подписка из Telegram")
        }}
        item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Button(import,Modifier.weight(1f),shape=RoundedCornerShape(14.dp)){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text("Импорт")}
            OutlinedButton(scan,shape=RoundedCornerShape(14.dp)){Icon(Icons.Outlined.QrCodeScanner,"Сканировать QR")}
        }}
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(state.data.profiles.isNotEmpty()){
            item{Row(verticalAlignment=Alignment.CenterVertically){
                OutlinedButton(onClick={if(state.probing)controller.cancelProbes()else controller.ping()},enabled=!state.probeStopping,modifier=Modifier.weight(1f)){Icon(Icons.Outlined.Speed,null);Spacer(Modifier.width(8.dp));Text(if(state.probing)"Стоп · ${state.probeDone}/${state.probeTotal}"else "Проверить все")}
                IconButton(onClick={help=true}){Icon(Icons.Outlined.Info,"О проверке серверов",tint=Muted)}
            }}
            if(state.probing)item{LinearProgressIndicator(progress={state.probeDone.toFloat()/state.probeTotal.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())}
            item{OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),placeholder={Text("Подписка или сервер")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(16.dp))}
            item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){
                (listOf("")+state.data.profiles.map{it.region}.filter{it.isNotBlank()}.distinct().sorted()).forEach{r->FilterChip(selected=state.data.preferences.region==r&&state.data.preferences.mode==Mode.AUTO,onClick={controller.chooseRegion(r)},label={Text(regionName(r))},enabled=state.connection.phase!=Phase.STOPPING)}
            }}
        }else item{Section("НАЧНИТЕ С ПОДПИСКИ"){Text("Добавьте ссылку подписки, ключ сервера или QR-код.",color=Muted)}}
        prepared.forEach{view->
            val (group,matches,categories,visible)=view
            if(search.isBlank()||matches.isNotEmpty()){
                val expanded=group.id !in collapsed||search.isNotBlank()
                val page=(pages[group.id] ?: 0).coerceAtMost(ServerList.pages(visible.size)-1)
                val rows=ServerList.page(visible,page)
                item(key="group/${group.id}"){
                    LaunchedEffect(group.id,page,search,expanded,opened,state.data.preferences.pingOnOpen){
                        if(expanded&&opened){delay(400);controller.pingVisible(rows.map{it.id}.toSet())}
                    }
                    SubscriptionHeader(group,expanded,state.busy,state.probing,
                        toggle={collapsed=if(expanded)collapsed+group.id else collapsed-group.id},
                        refresh={controller.refreshSubscription(group.id)},
                        ping={controller.ping(ids=group.profiles.map{it.id}.toSet())},
                        pin={controller.pinSubscription(group.id,group.source?.pinned!=true)},
                        edit={editing=group},remove={deletingSource=group},selection={selectionSource=group},preferences=state.data.preferences,reserves=group.profiles.count{it.id in reserveIds})
                    if(expanded)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        FilterChip(selected=states[group.id]==null,onClick={states=states+(group.id to null);pages=pages+(group.id to 0)},label={Text("Все · ${matches.size}")})
                        RouteState.entries.forEach{status->FilterChip(selected=states[group.id]==status,onClick={states=states+(group.id to status);pages=pages+(group.id to 0)},label={Text("${status.title} · ${categories[status].orEmpty().size}")})}
                    }
                    if(expanded&&visible.size>ServerList.PAGE_SIZE)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                        TextButton(enabled=page>0,onClick={pages=pages+(group.id to page-1)}){Text("←")}
                        Text("${page*ServerList.PAGE_SIZE+1}–${(page*ServerList.PAGE_SIZE+rows.size)} из ${visible.size} · ${page+1}/${ServerList.pages(visible.size)}",fontSize=12.sp,color=Muted)
                        TextButton(enabled=page+1<ServerList.pages(visible.size),onClick={pages=pages+(group.id to page+1)}){Text("→")}
                    }
                }
                if(expanded){
                    if(visible.isEmpty())item{Text("Нет серверов. Обновите подписку.",fontSize=12.sp,color=Muted)}
                    items(rows,key={"${group.id}/${it.id}"}){profile->ServerCard(profile,state,controller,profile.id in reserveIds){deleting=profile}}
                }
            }
        }
    }
    if(help)AlertDialog(onDismissRequest={help=false},title={Text("Пинг через прокси")},text={Text("HTTPS GET к адресу из настроек; по умолчанию www.gstatic.com/generate_204. Время включает новое соединение и TLS. Smart Balance — отдельный маршрут с автоматическим выбором узла внутри группы. OpenFlux и olcRTC проверяются только при свободном движке. Нажатие на сервер переключает VPN без его отключения.")},confirmButton={TextButton(onClick={help=false}){Text("Понятно")}})
    editing?.let{group->group.source?.let{source->SubscriptionEditor(source,group.name,state.busy,{editing=null}){name,url->controller.editSubscription(source.id,name,url){editing=null}}}}
    selectionSource?.let{group->SelectionSourceDialog(group,state.data.preferences,{selectionSource=null}){auto,smart,reserve->controller.selectionSource(group.id,auto,smart,reserve);selectionSource=null}}
    deletingSource?.let{group->AlertDialog(onDismissRequest={deletingSource=null},title={Text("Удалить подписку?")},text={Text("${group.name}\nСсылка и принадлежащие только ей серверы будут удалены. Если активный сервер входит только в неё, Rayren выберет другой маршрут. При отсутствии маршрутов трафик останется заблокирован до вашего отключения VPN.")},confirmButton={TextButton(enabled=!state.busy&&state.connection.phase!=Phase.STOPPING,onClick={controller.removeSubscription(group.id);deletingSource=null}){Text("Удалить",color=scheme.error)}},dismissButton={TextButton(onClick={deletingSource=null}){Text("Отмена")}})}

    deleting?.let{profile->AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить профиль?")},text={Text("${profile.name}\nПрофиль из подписки может вернуться при её обновлении.")},confirmButton={TextButton(onClick={controller.remove(profile.id);deleting=null}){Text("Удалить")}},dismissButton={TextButton(onClick={deleting=null}){Text("Отмена")}})}
}

@Composable private fun SubscriptionHeader(group:ServerGroup,expanded:Boolean,busy:Boolean,probing:Boolean,toggle:()->Unit,refresh:()->Unit,ping:()->Unit,pin:()->Unit,edit:()->Unit,remove:()->Unit,selection:()->Unit,preferences:Preferences,reserves:Int){
    var menu by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().padding(top=12.dp).clip(RoundedCornerShape(16.dp)).background(Mint.copy(alpha=.07f))){
        Row(Modifier.fillMaxWidth().clickable(onClick=toggle).padding(start=12.dp,top=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(if(expanded)Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,null,tint=Mint)
            Column(Modifier.weight(1f).padding(start=7.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    if(group.source?.pinned==true){Icon(Icons.Outlined.PushPin,"Закреплена",Modifier.size(14.dp),tint=Mint);Spacer(Modifier.width(5.dp))}
                    Text(group.name,fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Text("${group.profiles.size} маршрутов"+(group.source?.lastSuccess?.takeIf{it>0}?.let{" · "+SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(Date(it))} ?: ""),color=Muted,fontSize=10.sp)
                val auto=!(group.source?.excludeAuto ?: preferences.excludeManualAuto)
                val smart=!(group.source?.excludeSmart ?: preferences.excludeManualSmart)
                Text(listOfNotNull(if(auto)"Авто"else null,if(smart)"По пингу"else null).joinToString(" · ").ifBlank{"Только ручной выбор"}+" · БС: $reserves",color=Muted,fontSize=11.sp)
            }
            if(group.source!=null)Box{
                IconButton(onClick={menu=true},enabled=!busy){Icon(Icons.Outlined.MoreVert,"Действия с подпиской",tint=Muted)}
                DropdownMenu(menu,{menu=false}){
                    DropdownMenuItem(text={Text("Редактировать")},leadingIcon={Icon(Icons.Outlined.Edit,null)},onClick={menu=false;edit()})
                    DropdownMenuItem(text={Text(if(group.source?.pinned==true)"Открепить"else "Закрепить")},leadingIcon={Icon(Icons.Outlined.PushPin,null)},onClick={menu=false;pin()})
                    DropdownMenuItem(text={Text("Удалить подписку",color=scheme.error)},leadingIcon={Icon(Icons.Outlined.DeleteOutline,null)},onClick={menu=false;remove()})
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start=12.dp,end=8.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            if(group.source!=null)TextButton(onClick=refresh,enabled=!busy){Icon(Icons.Outlined.Refresh,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Обновить")}
            TextButton(onClick=ping,enabled=!probing&&!busy&&group.profiles.isNotEmpty()){Icon(Icons.Outlined.Speed,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Пинг")}
            TextButton(onClick=selection,enabled=!busy){Icon(Icons.Outlined.Tune,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Подбор")}
            if(group.source!=null)IconButton(onClick=remove,enabled=!busy){Icon(Icons.Outlined.DeleteOutline,"Удалить подписку",tint=scheme.error)}
        }
    }
}

@Composable private fun SelectionSourceDialog(group:ServerGroup,p:Preferences,close:()->Unit,save:(Boolean,Boolean,ReservePolicy)->Unit){
    var auto by remember{mutableStateOf(!(group.source?.excludeAuto ?: p.excludeManualAuto))}
    var smart by remember{mutableStateOf(!(group.source?.excludeSmart ?: p.excludeManualSmart))}
    var reserve by remember{mutableStateOf(group.source?.reservePolicy ?: ReservePolicy.DETECT)}
    AlertDialog(onDismissRequest=close,title={Text("Подбор · ${group.name}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("Использовать в «Авто»",Modifier.weight(1f));Switch(auto,{auto=it})}
        Row(verticalAlignment=Alignment.CenterVertically){Text("Использовать в «По пингу»",Modifier.weight(1f));Switch(smart,{smart=it})}
        if(group.source!=null){
            Text("Тип серверов подписки",fontWeight=FontWeight.SemiBold)
            ReservePolicy.entries.forEach{option->Row(Modifier.fillMaxWidth().selectable(reserve==option,onClick={reserve=option}),verticalAlignment=Alignment.CenterVertically){RadioButton(reserve==option,onClick={reserve=option});Text(when(option){ReservePolicy.DETECT->"Определять автоматически";ReservePolicy.RESERVE->"БС / резерв";ReservePolicy.REGULAR->"Обычные серверы"})}}
            Text("Автоопределение учитывает OpenFlux/Cups, olcRTC и пометки БС / BS / Whitelist в названиях. Работа у вашего оператора подтверждается проверкой подключения.",fontSize=12.sp,color=Muted)
        }
        Text("В «Авто» БС включается после отказа обычных выбранных серверов. Ручной выбор всегда доступен. При работающем автоматическом режиме изменение запускает новый подбор.",fontSize=12.sp,color=Muted)
    }},confirmButton={Button(onClick={save(auto,smart,reserve)}){Text("Сохранить")}},dismissButton={TextButton(close){Text("Отмена")}})
}

@Composable private fun SubscriptionEditor(source:Subscription,fallbackName:String,busy:Boolean,close:()->Unit,save:(String,String)->Unit){
    var name by remember(source.id){mutableStateOf(source.name.orEmpty())}
    var url by remember(source.id){mutableStateOf(source.url)}
    var visible by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val context=LocalContext.current
    AlertDialog(onDismissRequest={if(!busy)close()},properties=androidx.compose.ui.window.DialogProperties(securePolicy=androidx.compose.ui.window.SecureFlagPolicy.SecureOn),title={Text("Редактировать подписку")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(name,{name=it.take(80)},Modifier.fillMaxWidth(),label={Text("Название")},placeholder={Text(fallbackName)},singleLine=true,enabled=!busy)
            OutlinedTextField(url,{url=it.take(4096);error=""},Modifier.fillMaxWidth(),label={Text("Ссылка подписки")},visualTransformation=if(visible)androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),maxLines=5,enabled=!busy,
                trailingIcon={IconButton(onClick={visible=!visible}){Icon(if(visible)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,if(visible)"Скрыть ссылку"else "Показать ссылку")}})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={
                    val clip=android.content.ClipData.newPlainText("Подписка Rayren",url)
                    if(android.os.Build.VERSION.SDK_INT>=33)clip.description.extras=android.os.PersistableBundle().apply{putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true)}
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                }){Icon(Icons.Outlined.ContentCopy,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("Копировать")}
                TextButton(enabled=!busy,onClick={url=(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(4096);error=""}){Text("Вставить")}
            }
            Text("Новая ссылка проверяется при сохранении. Если она не работает, прежняя подписка останется.",fontSize=12.sp,color=Muted)
            if(url.trim().startsWith("http://"))Text("HTTP передаёт ссылку и ключи без шифрования. Используйте HTTPS, если он доступен.",fontSize=12.sp,color=Amber)
            if(error.isNotBlank())Text(error,color=scheme.error,fontSize=12.sp)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={Button(enabled=!busy&&url.isNotBlank(),onClick={try{save(name,ImportParser.subscriptionUrl(url))}catch(e:ValidationException){error=e.reason}}){Text(if(busy)"Проверяем…"else "Сохранить")}},dismissButton={TextButton(onClick=close,enabled=!busy){Text("Отмена")}})
}

@Composable private fun ServerCard(profile:Profile,state:AppState,controller:AppController,reserve:Boolean,remove:()->Unit){
    val selected=state.data.preferences.mode==Mode.MANUAL&&state.data.preferences.selectedId==profile.id
    val current=state.connection.profileId==profile.id&&state.connection.phase==Phase.CONNECTED
    Surface(shape=RoundedCornerShape(18.dp),color=Panel,border=if(selected||current)BorderStroke(1.dp,Mint.copy(alpha=.65f))else null){
        Row(Modifier.fillMaxWidth().clickable(enabled=state.connection.phase!=Phase.STOPPING){controller.choose(profile.id)}.padding(start=15.dp,top=15.dp,bottom=15.dp,end=5.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Mint.copy(alpha=.1f)),contentAlignment=Alignment.Center){Icon(if(profile.isBalancer)Icons.Outlined.Hub else when(profile.engine){Engine.XRAY->Icons.Outlined.Bolt;Engine.OPENFLUX->Icons.Outlined.Waves;Engine.OLCRTC->Icons.Outlined.Science},null,tint=if(profile.experimental)Amber else Mint)}
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)){
                Text(profile.name,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium)
                Text(if(profile.isBalancer)"Балансировщик · ${profile.xrayOutbounds().size} серверов"else "${engineName(profile.engine)} · ${profile.protocol}",fontSize=11.sp,color=Muted)
                if(profile.experimental)Text("Экспериментальный",color=Amber,fontSize=10.sp)
                if(reserve)Text("БС · резерв",color=Amber,fontSize=10.sp)
                if(current||selected)Text(if(current)"Подключён"else "Выбран",color=Mint,fontSize=11.sp)
                state.probes[profile.id]?.let{result->Text(when(result.state){ProbeState.OK->"${result.ms} мс · HTTPS";ProbeState.CHECKING->"Проверяем…";ProbeState.FAILED->"Нет ответа";ProbeState.CANCELLED->"Проверка отменена";ProbeState.BUSY->"Движок занят подключением"},fontSize=12.sp,color=if(result.state==ProbeState.OK)Mint else Muted)}
            }
            IconButton(onClick={controller.ping(profile.id)},enabled=(!state.probing||current)&&state.probes[profile.id]?.state!=ProbeState.CHECKING&&(!profile.experimental||state.data.preferences.experimental)){Icon(Icons.Outlined.Speed,"Пинг ${profile.name}",tint=Mint)}
            IconButton(onClick=remove){Icon(Icons.Outlined.MoreHoriz,"Удалить профиль",tint=Muted)}
        }
    }
}

@Composable private fun ImportDialog(initial:String,close:()->Unit,import:(String,String)->Unit,scan:()->Unit){
    val context=LocalContext.current
    var value by remember(initial){mutableStateOf(initial)};var key by remember{mutableStateOf("")};var advanced by remember{mutableStateOf(false)}
    val personal=remember(value){(runCatching{ImportInputResolver.resolve(value)}.getOrNull() as? ImportInput.Remote)?.url?.let(PersonalSubscription::isUrl)==true}
    val unencrypted=remember(value){(runCatching{ImportInputResolver.resolve(value)}.getOrNull() as? ImportInput.Remote)?.url?.startsWith("http://")==true}
    DisposableEffect(Unit){val window=(context as? Activity)?.window;window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);onDispose{window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}}
    AlertDialog(onDismissRequest=close,title={Text("Добавить подключение")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(if(personal)"Добавить личный аккаунт Rayren? Основные серверы и БС, если он вам доступен, загрузятся вместе. Повторный импорт обновит этот аккаунт. VPN сам не включится."else "Вставьте ссылку подписки, ключ сервера или JSON. Импорт начнётся после нажатия кнопки.",color=Muted,fontSize=13.sp)
            if(!personal)OutlinedTextField(value,{value=it.take(ImportLimits.MAX_BYTES)},Modifier.fillMaxWidth().heightIn(min=110.dp,max=200.dp),label={Text("Ссылка или конфигурация")},visualTransformation=PasswordVisualTransformation(),shape=RoundedCornerShape(12.dp))
            if(unencrypted)Text("Эта подписка использует HTTP: ссылка и ключи передаются без шифрования. Если есть HTTPS-ссылка, используйте её.",color=Muted,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip;value=clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(ImportLimits.MAX_BYTES)}){Icon(Icons.Outlined.ContentPaste,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("Вставить")}
                TextButton(onClick={close();scan()}){Icon(Icons.Outlined.QrCodeScanner,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("QR-код")}
            }
            TextButton(onClick={advanced=!advanced}){Text("Проверка подписи",fontSize=12.sp)}
            if(advanced){Text("Для подписанного manifest укажите публичный ключ Ed25519 от владельца подписки. Получите его отдельным доверенным способом.",fontSize=12.sp,color=Muted);OutlinedTextField(key,{key=it},label={Text("Публичный ключ · Base64URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)}
        }
    },confirmButton={Button(onClick={import(value,key)},enabled=value.isNotBlank()){Text("Импортировать")}},dismissButton={TextButton(close){Text("Отмена")}})
}

@Composable private fun <T> Choice(label:String,value:T,options:List<Pair<T,String>>,set:(T)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Column{
        Text(label,color=Muted,fontSize=12.sp)
        Box{Row(Modifier.fillMaxWidth().clickable{expanded=true}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Text(options.find{it.first==value}?.second ?: value.toString(),Modifier.weight(1f));Icon(Icons.Outlined.ExpandMore,null,tint=Muted)}
            DropdownMenu(expanded,{expanded=false}){options.forEach{(option,name)->DropdownMenuItem(text={Text(name)},onClick={set(option);expanded=false})}}
        }
    }
}
@Composable private fun Toggle(title:String,checked:Boolean,subtitle:String="",set:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(end=12.dp)){Text(title,fontSize=14.sp);if(subtitle.isNotBlank())Text(subtitle,color=Muted,fontSize=11.sp)};Switch(checked,set)}
}
@Composable private fun NumberInput(label:String,value:String,set:(String)->Unit){OutlinedTextField(value,set,Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),shape=RoundedCornerShape(12.dp))}

@Composable private fun SettingsPage(state:AppState,controller:AppController,systemVpn:()->Unit){
    var p by remember(state.data.preferences){mutableStateOf(state.data.preferences.let{if(it.preset==Preset.RUSSIA_DIRECT)it.copy(preset=Preset.GLOBAL,bypassRuDomains=true)else it})}
    var section by rememberSaveable{mutableStateOf("")}
    var timeout by remember(state.data.preferences){mutableStateOf((p.timeoutMs/1000).toString())}
    var health by remember(state.data.preferences){mutableStateOf(p.healthSeconds.toString())}
    var refresh by remember(state.data.preferences){mutableStateOf(p.refreshHours.toString())}
    var mtu by remember(state.data.preferences){mutableStateOf(p.mtu.toString())}
    var expected by remember(state.data.preferences){mutableStateOf(p.probeStatus.toString())}
    if(section=="apps"){AppsPage(p,{p=it},{section="bypass"});return}
    if(section=="rules"){RulesPage(p.rules,{p=p.copy(rules=it)},{section=""},controller::message);return}
    if(section=="dnsrules"){DnsRulesPage(p,{p=it},{section=""},controller::message);return}
    if(section=="bypass"){BypassPage(p,{p=it},{section=""},{controller.save(p)},state.connection.phase==Phase.IDLE,controller::message,{if(p.appMode==AppMode.ALL)p=p.copy(appMode=AppMode.EXCLUDE);section="apps"});return}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Heading("Настройки","Контроль подключения и приватности")
        if(state.connection.phase!=Phase.IDLE)Text("Сортировку, пинг при открытии и обновление подписок можно сохранить сейчас. Изменения подключения и обхода требуют отключения VPN.",color=Amber,fontSize=13.sp)
        Section("ОБХОД VPN"){
            Text("Российские приложения и сайты",fontWeight=FontWeight.Medium)
            Text("Готовые группы сервисов, домены .ru / .рф и ваши исключения. Приложения подбираются из установленных на телефоне.",color=Muted,fontSize=13.sp)
            TextButton(onClick={section="bypass"}){Text("Сайты и приложения · настроить")}
            Text("Группы сайтов: ${p.bypassSiteGroups.size} · группы приложений: ${p.bypassAppGroups.size} · свои сайты: ${p.directSites.size} · приложения вручную: ${p.packages.size}",fontSize=11.sp,color=Muted)
        }
        Section("ПОДКЛЮЧЕНИЕ"){
            Toggle("Переподключаться в ручном режиме",p.reconnect,"В автоматических режимах восстановление включено всегда"){p=p.copy(reconnect=it)}
            Toggle("Экспериментальный olcRTC",p.experimental,"Telemost, Jitsi и WB Stream; UDP ограничен"){p=p.copy(experimental=it)}
            NumberInput("Таймаут, секунд",timeout){timeout=it}
            NumberInput("Проверка соединения, секунд",health){health=it}
        }
        Section("ТУННЕЛЬ"){
            Choice("Маршрутизация",p.preset,listOf(Preset.GLOBAL to "Всё через VPN",Preset.PROXY_ONLY to "Только по правилам PROXY",Preset.CUSTOM to "Свои правила, остальное через VPN")){p=p.copy(preset=it)}
            Toggle("Доступ к локальной сети",p.lanBypass,"Прямое соединение с локальными адресами"){p=p.copy(lanBypass=it)}
            Choice("IPv6",p.ipv6,listOf(Ipv6Mode.BLOCK to "Блокировать IPv6",Ipv6Mode.DUAL to "IPv4 + IPv6, если транспорт поддерживает",Ipv6Mode.PREFER_IPV4 to "Предпочитать IPv4",Ipv6Mode.PREFER_IPV6 to "Предпочитать IPv6")){p=p.copy(ipv6=it)}
            Text("На OpenFlux и olcRTC IPv6 блокируется. Весь IPv6-трафик остаётся внутри VPN-интерфейса.",color=Muted,fontSize=11.sp)
            TextButton(onClick={section="rules"}){Icon(Icons.Outlined.Route,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Правила · ${p.rules.size}")}
            Toggle("Автоматический MTU",p.autoMtu,"Безопасный размер пакета: 1280"){p=p.copy(autoMtu=it)}
            if(!p.autoMtu)NumberInput("MTU · 1280–1500",mtu){mtu=it}
            TextButton(systemVpn){Icon(Icons.Outlined.Security,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Постоянный VPN и блокировка")}
            Text("В Android включите «Постоянный VPN» и «Блокировать подключения без VPN». Это защищает трафик при остановке процесса. Исключённые приложения при такой блокировке могут потерять доступ к сети.",color=Muted,fontSize=11.sp)
        }
        Section("DNS"){
            Choice("Режим DNS",p.dnsMode,listOf(DnsMode.TUNNEL_DOH to "DoH через VPN",DnsMode.TUNNEL_DOT to "DoT через VPN",DnsMode.TUNNEL_UDP to "DNS через VPN",DnsMode.DIRECT_SYSTEM to "Системный DNS напрямую",DnsMode.DIRECT_CUSTOM to "Свой DNS напрямую")){mode->p=p.copy(dnsMode=mode,dns=when(mode){DnsMode.TUNNEL_DOH->"https://1.1.1.1/dns-query";DnsMode.TUNNEL_DOT->"tls://1.1.1.1:853";else->"1.1.1.1"},strictDns=mode in setOf(DnsMode.TUNNEL_DOH,DnsMode.TUNNEL_DOT,DnsMode.TUNNEL_UDP))}
            if(p.dnsMode!=DnsMode.DIRECT_SYSTEM)OutlinedTextField(p.dns,{p=p.copy(dns=it)},Modifier.fillMaxWidth(),label={Text(when(p.dnsMode){DnsMode.TUNNEL_DOH->"HTTPS URL резолвера";DnsMode.TUNNEL_DOT->"tls://DNS-сервер:853";else->"IP DNS-сервера"})},singleLine=true,shape=RoundedCornerShape(12.dp))
            Toggle("Запрещать DNS вне VPN",p.strictDns,"DNS приложений направляется в выбранный туннель"){p=p.copy(strictDns=it)}
            TextButton(onClick={section="dnsrules"}){Text("Раздельный DNS · ${p.dnsRules.size} правил")}
            Text("Для установления самого транспорта используется DNS физической сети. Private DNS Android: ${if(state.connection.privateDns)"включён" else "не обнаружен / VPN не активен"}.",color=Muted,fontSize=11.sp)
        }
        Section("ПРОВЕРКИ И ОБНОВЛЕНИЯ"){
            Toggle("Сортировать серверы по пингу",p.sortServersByPing,"Внутри каждой подписки; без ответа — внизу"){p=p.copy(sortServersByPing=it)}
            Toggle("Пинг при открытии серверов",p.pingOnOpen,"До 50 серверов открытой страницы; свежие замеры не повторяются 5 минут"){p=p.copy(pingOnOpen=it)}
            Toggle("Обновлять при открытии серверов",p.refreshOnOpen,"Не чаще раза в 5 минут"){p=p.copy(refreshOnOpen=it)}
            Toggle("Автообновление подписок",!p.disableAutoRefresh,"В фоне при доступной сети"){p=p.copy(disableAutoRefresh=!it)}
            OutlinedTextField(p.probeUrl,{p=p.copy(probeUrl=it)},Modifier.fillMaxWidth(),label={Text("HTTPS URL проверки")},shape=RoundedCornerShape(12.dp))
            NumberInput("Ожидаемый HTTP статус",expected){expected=it}
            NumberInput("Обновлять подписки каждые, часов",refresh){refresh=it}
            Toggle("Расширенный журнал",p.debug,"Больше событий; без ключей и URL"){p=p.copy(debug=it)}
        }
        Button(onClick={
            val values=listOf(timeout,health,refresh,mtu,expected).map{it.toIntOrNull()}
            if(values.any{it==null})controller.message("Заполните числовые параметры")else controller.save(p.copy(timeoutMs=values[0]!!*1000,healthSeconds=values[1]!!,refreshHours=values[2]!!,mtu=values[3]!!,probeStatus=values[4]!!))
        },Modifier.fillMaxWidth().height(52.dp),enabled=!state.busy&&!state.fatal,shape=RoundedCornerShape(16.dp)){Text("Сохранить настройки")}
        Text("Rayren · GPLv3 · ${BuildConfig.VERSION_NAME}\nXray, OpenFlux, olcRTC и HEV\nЛокальная сборка. Без обязательных сервисов Google.",color=Muted,fontSize=11.sp)
    }
}

@Composable private fun BypassPage(p:Preferences,set:(Preferences)->Unit,back:()->Unit,save:()->Unit,canSave:Boolean,message:(String)->Unit,apps:()->Unit){
    val context=LocalContext.current
    var site by rememberSaveable{mutableStateOf("")}
    val installed by produceState<Map<String,String>?>(null){value=withContext(Dispatchers.IO){context.packageManager.getInstalledApplications(0).associate{it.packageName to it.loadLabel(context.packageManager).toString()}}}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=22.dp),contentPadding=PaddingValues(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")};Text("Обход VPN",fontSize=26.sp)}}
        item{Text("Выбранные сайты открываются напрямую. У выбранных приложений весь трафик идёт вне VPN. Это готовый список популярных сервисов, а не определение страны каждого приложения.",color=Muted,fontSize=13.sp)}
        item{Section("БЫСТРЫЙ ВЫБОР"){
            Button(onClick={set(p.copy(bypassRuDomains=true,bypassSiteGroups=RussianBypass.ids,bypassAppGroups=RussianBypass.ids))},modifier=Modifier.fillMaxWidth()){Text("Выбрать популярные российские")}
            TextButton(onClick={set(p.copy(bypassRuDomains=false,bypassWhitelistSites=false,bypassSiteGroups=emptySet(),bypassAppGroups=emptySet(),preset=if(p.preset==Preset.RUSSIA_DIRECT)Preset.GLOBAL else p.preset))}){Text("Сбросить автоматический выбор")}
            Toggle("Все сайты .ru и .рф",p.bypassRuDomains||p.preset==Preset.RUSSIA_DIRECT,"Включая поддомены. Это доменные зоны, а не геолокация сайта."){set(p.copy(bypassRuDomains=it,preset=if(p.preset==Preset.RUSSIA_DIRECT)Preset.GLOBAL else p.preset))}
            Toggle("Сайты из БС напрямую",p.bypassWhitelistSites,"Сервисы и CDN, включая .com и .net. Для этих сайтов DNS тоже идёт через оператора. Доступность зависит от сети; остальные сайты остаются через VPN."){set(p.copy(bypassWhitelistSites=it))}
            if(p.bypassWhitelistSites)Text(WhitelistBypass.domains.joinToString(", "),fontSize=11.sp,color=Muted)
        }}
        item{Section("ПРИЛОЖЕНИЯ ВРУЧНУЮ"){
            Choice("Обработка приложений",p.appMode,listOf(AppMode.ALL to "Все через VPN, кроме автообхода",AppMode.EXCLUDE to "Отмеченные в обход VPN",AppMode.INCLUDE to "Только отмеченные через VPN")){set(p.copy(appMode=it))}
            OutlinedButton(onClick=apps,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Apps,null);Spacer(Modifier.width(8.dp));Text("Выбрать приложения · ${p.packages.size}")}
        }}
        items(RussianBypass.groups,key={it.id}){group->
            val found=installed.orEmpty().filterKeys{it in group.packages}.values.sorted()
            Section(group.name){
                Toggle("Сайты",group.id in p.bypassSiteGroups,group.domains.joinToString(", ")){checked->set(p.copy(bypassSiteGroups=if(checked)p.bypassSiteGroups+group.id else p.bypassSiteGroups-group.id))}
                Toggle("Приложения · ${if(installed==null)"поиск…" else "найдено ${found.size}"}",group.id in p.bypassAppGroups,if(found.isEmpty())"Подходящих установленных приложений не найдено. Новые будут учтены при следующем подключении." else found.joinToString(", ")){checked->set(p.copy(bypassAppGroups=if(checked)p.bypassAppGroups+group.id else p.bypassAppGroups-group.id))}
            }
        }
        item{Section("СВОИ САЙТЫ"){
            OutlinedTextField(site,{site=it},modifier=Modifier.fillMaxWidth(),label={Text("Домен или ссылка сайта")},singleLine=true)
            TextButton(onClick={try{val domain=RussianBypass.site(site);ensure(p.directSites.size<128||domain in p.directSites,"Можно добавить до 128 сайтов");set(p.copy(directSites=p.directSites+domain));site=""}catch(e:ValidationException){message(e.reason)}}){Text("Добавить в обход")}
            p.directSites.sorted().forEach{domain->Row(verticalAlignment=Alignment.CenterVertically){Text(domain,Modifier.weight(1f),fontSize=13.sp);IconButton(onClick={set(p.copy(directSites=p.directSites-domain))}){Icon(Icons.Outlined.Close,"Удалить $domain")}}}
        }}
        item{Text("Ваши отдельные правила имеют приоритет. Для сайтов из БС используется DNS оператора, для остальных — выбранный режим DNS.",color=Muted,fontSize=12.sp)}
        item{Text("Если в Android включена блокировка подключений без VPN, исключённые приложения могут остаться без интернета.",color=Muted,fontSize=12.sp)}
        if(!canSave)item{Text("Для сохранения обхода отключите VPN большой круглой кнопкой. Переключение серверов этого не требует.",color=Amber,fontSize=13.sp)}
        item{Button(onClick=save,enabled=canSave,modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Сохранить обход")}}
    }
}

private data class InstalledApp(val pkg:String,val name:String,val system:Boolean,val info:ApplicationInfo)
@Composable private fun AppsPage(p:Preferences,set:(Preferences)->Unit,back:()->Unit){
    val context=LocalContext.current
    var query by remember{mutableStateOf("")};var system by remember{mutableStateOf(false)}
    val apps by produceState<List<InstalledApp>?>(null){value=withContext(Dispatchers.IO){context.packageManager.getInstalledApplications(0).filter{it.packageName!=context.packageName}.map{InstalledApp(it.packageName,it.loadLabel(context.packageManager).toString(),it.flags and ApplicationInfo.FLAG_SYSTEM!=0,it)}.sortedBy{it.name.lowercase()}}}
    LazyColumn(Modifier.fillMaxSize().padding(22.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")};Text("Приложения",fontSize=24.sp)}}
        item{Choice("Режим",p.appMode,listOf(AppMode.ALL to "Все через VPN, кроме автообхода",AppMode.INCLUDE to "Только отмеченные через VPN",AppMode.EXCLUDE to "Отмеченные в обход VPN")){set(p.copy(appMode=it))}}
        item{Text("Автообход российских приложений имеет приоритет. Меняйте его в разделе «Обход VPN».",color=Muted,fontSize=12.sp)}
        item{Text("Вернитесь назад и нажмите «Сохранить обход». Rayren исключён из VPN для работы транспортов.",color=Muted,fontSize=12.sp)}
        item{OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Поиск по имени или пакету")},singleLine=true,shape=RoundedCornerShape(12.dp))}
        item{Toggle("Системные приложения",system){system=it}}
        if(apps==null)item{CircularProgressIndicator()}
        items(apps.orEmpty().filter{(system||!it.system||it.pkg in p.packages)&&("${it.name} ${it.pkg}".contains(query,true))},key={it.pkg}){app->
            Row(Modifier.fillMaxWidth().clickable(enabled=p.appMode!=AppMode.ALL){set(p.copy(packages=if(app.pkg in p.packages)p.packages-app.pkg else p.packages+app.pkg))}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                val bitmap=remember(app.pkg){runCatching{app.info.loadIcon(context.packageManager).toBitmap(80,80).asImageBitmap()}.getOrNull()}
                if(bitmap!=null)Image(bitmap,null,Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))else Icon(Icons.Outlined.Apps,null,Modifier.size(36.dp))
                Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(app.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=14.sp);Text(app.pkg,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=10.sp,color=Muted);if(app.pkg in RussianBypass.packages(p))Text("Автообход: вне VPN",fontSize=11.sp,color=Mint)}
                Checkbox(app.pkg in p.packages,{checked->set(p.copy(packages=if(checked)p.packages+app.pkg else p.packages-app.pkg))},enabled=p.appMode!=AppMode.ALL)
            }
        }
        item{Spacer(Modifier.height(16.dp))}
    }
}

@Composable private fun RulesPage(rules:List<Rule>,set:(List<Rule>)->Unit,back:()->Unit,message:(String)->Unit){
    var adding by remember{mutableStateOf(false)};var kind by remember{mutableStateOf(RuleKind.SUFFIX)};var value by remember{mutableStateOf("")};var target by remember{mutableStateOf(Target.PROXY)}
    LazyColumn(Modifier.fillMaxSize().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")};Text("Правила",fontSize=24.sp,modifier=Modifier.weight(1f));IconButton(onClick={adding=true}){Icon(Icons.Outlined.Add,"Добавить правило")}}}
        item{Text("Сверху вниз: первое совпадение определяет маршрут. Защита DNS и IPv6 имеет приоритет. Затем ваши правила, локальная сеть, российские домены и маршрут по умолчанию.",fontSize=12.sp,color=Muted)}
        items(rules.size){index->val r=rules[index]
            Section("${index+1} · ${r.kind}"){
                Text("${r.value} → ${r.target}")
                Row{
                    IconButton(onClick={val copy=rules.toMutableList();val old=copy[index-1];copy[index-1]=r;copy[index]=old;set(copy)},enabled=index>0){Icon(Icons.Outlined.ArrowUpward,"Выше")}
                    IconButton(onClick={val copy=rules.toMutableList();val old=copy[index+1];copy[index+1]=r;copy[index]=old;set(copy)},enabled=index<rules.lastIndex){Icon(Icons.Outlined.ArrowDownward,"Ниже")}
                    Spacer(Modifier.weight(1f));IconButton(onClick={set(rules.filterIndexed{i,_->i!=index})}){Icon(Icons.Outlined.DeleteOutline,"Удалить")}
                }
            }
        }
        item{Text("DIRECT — напрямую с телефона. PROXY — через выбранный транспорт. BLOCK — блокировать. Изменения применяются кнопкой сохранения в настройках.",fontSize=12.sp,color=Muted)}
    }
    if(adding)AlertDialog(onDismissRequest={adding=false},title={Text("Новое правило")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Choice("Совпадение",kind,listOf(RuleKind.SUFFIX to "Домен и поддомены",RuleKind.DOMAIN to "Точный домен",RuleKind.IP to "IP / CIDR",RuleKind.GEOIP to "GeoIP",RuleKind.GEOSITE to "GeoSite",RuleKind.PORT to "Порт / диапазон",RuleKind.NETWORK to "TCP / UDP",RuleKind.PROTOCOL to "Протокол приложения")){kind=it}
        OutlinedTextField(value,{value=it},label={Text("Значение правила")},singleLine=true)
        Choice("Действие",target,Target.entries.map{it to it.name}){target=it}
    }},confirmButton={TextButton(onClick={try{val rule=Rule(kind,value.trim(),target);PolicyCompiler.validateRule(rule);set(rules+rule);value="";adding=false}catch(e:ValidationException){message(e.reason)}}){Text("Добавить")}},dismissButton={TextButton(onClick={adding=false}){Text("Отмена")}})
}

@Composable private fun DnsRulesPage(p:Preferences,set:(Preferences)->Unit,back:()->Unit,message:(String)->Unit){
    var suffix by remember{mutableStateOf("")};var server by remember{mutableStateOf("1.1.1.1")};var direct by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Назад")};Text("Раздельный DNS",fontSize=24.sp)}
        Text("Задайте DNS для домена и его поддоменов. При совпадении используется только указанный резолвер. Прямой DNS требует отключить «Запрещать DNS вне VPN».",color=Muted,fontSize=12.sp)
        p.dnsRules.forEachIndexed{i,r->Section(r.suffix){Text(r.server,fontSize=13.sp);Row(verticalAlignment=Alignment.CenterVertically){Text(if(r.direct)"Напрямую"else "Через VPN",Modifier.weight(1f),color=Muted,fontSize=12.sp);IconButton(onClick={set(p.copy(dnsRules=p.dnsRules.filterIndexed{index,_->index!=i}))}){Icon(Icons.Outlined.DeleteOutline,"Удалить")}}}}
        OutlinedTextField(suffix,{suffix=it},Modifier.fillMaxWidth(),label={Text("Домен, например ru")},singleLine=true)
        OutlinedTextField(server,{server=it},Modifier.fillMaxWidth(),label={Text("IP или HTTPS URL DNS")},singleLine=true)
        Toggle("Запросы напрямую",direct){direct=it}
        Button(onClick={try{val updated=p.copy(dnsRules=p.dnsRules+DnsRule(suffix.trim(),server.trim(),direct));PolicyCompiler.validate(updated);set(updated);suffix=""}catch(e:ValidationException){message(e.reason)}}){Text("Добавить правило DNS")}
        Text("Вернитесь в настройки и нажмите «Сохранить».",color=Muted,fontSize=12.sp)
    }
}

@Composable private fun Diagnostics(state:AppState,controller:AppController,export:()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Heading("Диагностика","Проверки соединения и события")
        Section("СОСТОЯНИЕ"){
            Text(state.connection.detail.ifBlank{"VPN отключён"})
            Text("${state.connection.network}\nDNS: ${state.data.preferences.dnsMode}\nIPv6: ${state.data.preferences.ipv6}\nMTU: ${if(state.data.preferences.autoMtu)1280 else state.data.preferences.mtu}",color=Muted,fontSize=13.sp)
            Text("Подключение считается успешным после двух HTTPS-проверок через выбранный транспорт. IP выхода определяется отдельно.",color=Muted,fontSize=12.sp)
        }
        OutlinedButton(export,Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){Icon(Icons.Outlined.FileDownload,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Экспортировать отчёт")}
        Text("Отчёт исключает ключи, UUID, ссылки подписок, адреса серверов и IP выхода.",fontSize=12.sp,color=Muted)
        Section("СОБЫТИЯ"){
            if(state.events.isEmpty())Text("Событий пока нет",color=Muted,fontSize=13.sp)
            state.events.asReversed().take(200).forEach{Text(it,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace,fontSize=11.sp,color=Muted)}
        }
    }
}
private fun modeName(mode:Mode)=ModeText.short(mode)
private fun engineName(engine:Engine)=when(engine){Engine.XRAY->"Xray";Engine.OPENFLUX->"OpenFlux";Engine.OLCRTC->"olcRTC"}
private fun regionName(region:String)=when(region.uppercase()){ ""->"Все регионы";"RU"->"Россия";"FI"->"Финляндия";"NL"->"Нидерланды";else->region}
private fun bytes(n:Long):String=when{n<1024->"$n Б";n<1024*1024->"%.1f КБ".format(n/1024.0);n<1024L*1024*1024->"%.1f МБ".format(n/(1024.0*1024));else->"%.2f ГБ".format(n/(1024.0*1024*1024))}
private fun duration(seconds:Long):String="%02d:%02d:%02d".format(seconds/3600,(seconds/60)%60,seconds%60)
