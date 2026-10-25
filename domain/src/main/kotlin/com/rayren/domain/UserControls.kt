package com.rayren.domain

import java.net.IDN
import java.net.URI

object ModeText {
    fun title(mode:Mode)=when(mode){Mode.AUTO->"Авто · рабочий сервер с резервом";Mode.SMART->"По задержке · быстрый сервер";Mode.MANUAL->"Ручной · выбранный сервер"}
    fun short(mode:Mode)=when(mode){Mode.AUTO->"Авто";Mode.SMART->"По пингу";Mode.MANUAL->"Ручной"}
    fun description(mode:Mode)=when(mode){
        Mode.AUTO->"Сначала пробует обычные серверы в случайном порядке. БС, OpenFlux/Cups и olcRTC использует только после их неудачи. Пометка БС в названии сервера или подписки относит её к резерву. Рабочее соединение сохраняет до сбоя или смены сети."
        Mode.SMART->"При подключении и смене сети проверяет до 12 серверов, включая доступные альтернативные транспорты, и выбирает лучший из проверенных. После 45 секунд завершает текущую проверку и выбирает маршрут. Рабочее соединение сохраняется до сбоя или следующей смены сети."
        Mode.MANUAL->"Использует сервер, который вы выбрали в списке. При сбое повторяет подключение к нему, если включено переподключение. Можно выбрать другой сервер, не отключая VPN."
    }
}

data class BypassGroup(val id:String,val name:String,val domains:Set<String>,val packages:Set<String>)

/** Curated service groups, not a claim to identify the country of every installed app. */
object RussianBypass {
    val groups=listOf(
        BypassGroup("yandex","Яндекс · карты, такси, музыка, почта",setOf("yandex.ru","yandex.com","yandex.net","ya.ru","yastatic.net"),setOf("ru.yandex.yandexmaps","ru.yandex.yandexnavi","ru.yandex.taxi","ru.yandex.music","ru.yandex.market","ru.yandex.disk","ru.yandex.mail")),
        BypassGroup("vk","VK, Одноклассники и Почта Mail",setOf("vk.com","vk.ru","userapi.com","vkuseraudio.net","vkvideo.ru","ok.ru","mail.ru"),setOf("com.vkontakte.android","ru.ok.android","ru.mail.mailapp","com.vk.im","com.vk.vkvideo","com.vk.music")),
        BypassGroup("government","Госуслуги",setOf("gosuslugi.ru","esia.gosuslugi.ru"),setOf("ru.rostel","ru.gosuslugi.pos")),
        BypassGroup("banks","Сбер, Т-Банк, ВТБ и Альфа-Банк",setOf("sberbank.ru","sber.ru","tbank.ru","tinkoff.ru","vtb.ru","alfabank.ru"),setOf("ru.sberbankmobile","com.idamob.tinkoff.android","ru.vtb24.mobilebanking.android","ru.alfabank.mobile.android")),
        BypassGroup("shopping","Ozon, Wildberries и Авито",setOf("ozon.ru","ozon.com","ozone.ru","wildberries.ru","wb.ru","wbstatic.net","avito.ru","avito.st"),setOf("ru.ozon.app.android","com.wildberries.ru","com.avito.android")),
    )
    val ids get()=groups.map{it.id}.toSet()
    fun packages(p:Preferences)=groups.filter{it.id in p.bypassAppGroups}.flatMap{it.packages}.toSet()
    fun domains(p:Preferences):Set<String> = buildSet {
        if(p.bypassRuDomains||p.preset==Preset.RUSSIA_DIRECT){add("ru");add("xn--p1ai")}
        addAll(groups.filter{it.id in p.bypassSiteGroups}.flatMap{it.domains})
        addAll(p.directSites)
        if(p.bypassWhitelistSites)addAll(WhitelistBypass.domains)
    }
    fun includedPackages(p:Preferences,installed:Set<String>,own:String):Set<String> = (p.packages-packages(p)-own).intersect(installed)
    fun excludedPackages(p:Preferences,installed:Set<String>,own:String):Set<String> = ((if(p.appMode==AppMode.EXCLUDE)p.packages else emptySet())+packages(p)+own).intersect(installed+own)
    fun site(input:String):String {
        val value=input.trim().removePrefix("*.")
        val host=if(value.contains("://")){
            val uri=runCatching{URI(value)}.getOrNull()
            ensure(uri!=null&&uri.scheme in setOf("http","https")&&uri.userInfo==null,"Вставьте домен или HTTP/HTTPS-ссылку сайта")
            val authority=uri!!.rawAuthority.orEmpty()
            ensure('@' !in authority&&authority.count{it==':'}<=1,"Некорректный адрес сайта")
            if(':' in authority)ensure(authority.substringAfter(':').toIntOrNull() in 1..65535,"Некорректный порт сайта")
            uri.host ?: authority.substringBefore(':')
        }else value.trimEnd('/')
        val ascii=runCatching{IDN.toASCII(host.lowercase())}.getOrDefault("")
        ensure(ascii.length in 1..253&&ascii.split('.').all{it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))},"Некорректный домен сайта")
        return ascii
    }
}

/** Explicit starter catalogue; availability varies by mobile operator and region. */
object WhitelistBypass {
    val domains=setOf("gosuslugi.ru","esia.gosuslugi.ru","yandex.ru","yandex.com","yandex.net","ya.ru","yastatic.net",
        "vk.com","vk.ru","vkvideo.ru","userapi.com","vkuseraudio.net","ok.ru","mail.ru","mycdn.me",
        "max.ru","oneme.ru","ozon.ru","ozon.com","ozone.ru","ozonusercontent.com","wildberries.ru","wb.ru","wbstatic.net",
        "avito.ru","avito.st","rzd.ru","pass.rzd.ru","tutu.ru","aeroflot.ru","pobeda.aero",
        "pochta.ru","nalog.gov.ru","sberbank.ru","sber.ru","vtb.ru","tbank.ru","alfabank.ru",
        "mts.ru","megafon.ru","beeline.ru","t2.ru","rt.ru","2gis.ru","2gis.com")
}

enum class ProbeState { CHECKING, OK, FAILED, CANCELLED, BUSY }
data class ProbeResult(val state:ProbeState,val ms:Long?=null,val at:Long=System.currentTimeMillis())

object RouteChoice {
    fun select(p:Preferences,profile:Profile):Preferences {
        ensure(!profile.experimental||p.experimental,"Сначала включите экспериментальный olcRTC в настройках")
        return p.copy(mode=Mode.MANUAL,selectedId=profile.id,region="")
    }
    fun mode(p:Preferences,mode:Mode,profiles:List<Profile>,active:String):Preferences {
        val eligible=profiles.filter{!it.experimental||p.experimental}
        val selected=eligible.find{it.id==active} ?: eligible.find{it.id==p.selectedId} ?: eligible.firstOrNull()
        ensure(mode!=Mode.MANUAL||selected!=null,"Сначала добавьте и выберите сервер")
        return p.copy(mode=mode,selectedId=if(mode==Mode.MANUAL)selected!!.id else p.selectedId,region="")
    }
}
