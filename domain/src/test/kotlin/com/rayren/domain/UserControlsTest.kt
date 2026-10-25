package com.rayren.domain

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UserControlsTest {
    private val profile=ImportParser.parse(VLESS).profiles.single()
    @Test fun oldVaultKeepsDefaultsAndSecrets(){
        val vault=Gson().fromJson("""{"preferences":{"mode":"MANUAL","selectedId":"saved","packages":["com.example.app"],"preset":"RUSSIA_DIRECT"}}""",VaultData::class.java)
        val p=vault.preferences
        assertEquals("saved",p.selectedId);assertEquals(setOf("com.example.app"),p.packages)
        assertEquals(emptySet<String>(),p.bypassSiteGroups);assertEquals(emptySet<String>(),p.bypassAppGroups);assertEquals(emptySet<String>(),p.directSites)
        assertFalse(p.bypassRuDomains);assertEquals(setOf("ru","xn--p1ai"),RussianBypass.domains(p));PolicyCompiler.validate(p)
    }
    @Test fun routeChangePreservesUnrelatedSettings(){
        val p=Preferences(region="FI",dns="https://9.9.9.9/dns-query",bypassAppGroups=setOf("banks"),directSites=setOf("example.org"),rules=listOf(Rule(RuleKind.DOMAIN,"blocked.example",Target.BLOCK)))
        val changed=RouteChoice.select(p,profile)
        assertEquals(p.copy(mode=Mode.MANUAL,selectedId=profile.id,region=""),changed)
    }
    @Test fun experimentalSelectionNeedsOptIn(){bad{RouteChoice.select(Preferences(),profile.copy(engine=Engine.OLCRTC,experimental=true))}}
    @Test fun manualModeUsesActuallyActiveServer(){
        val other=profile.copy(id="other")
        assertEquals(other.id,RouteChoice.mode(Preferences(selectedId=profile.id),Mode.MANUAL,listOf(profile,other),other.id).selectedId)
        bad{RouteChoice.mode(Preferences(),Mode.MANUAL,emptyList(),"")}
    }
    @Test fun manualModeNeverRevivesDisabledExperimental(){
        val experimental=profile.copy(id="experimental",experimental=true)
        assertEquals(profile.id,RouteChoice.mode(Preferences(selectedId=experimental.id),Mode.MANUAL,listOf(experimental,profile),experimental.id).selectedId)
    }
    @Test fun automaticModeClearsRegionWithoutChangingRouting(){
        val p=Preferences(mode=Mode.MANUAL,region="RU",bypassRuDomains=true,selectedId=profile.id)
        assertEquals(p.copy(mode=Mode.SMART,region=""),RouteChoice.mode(p,Mode.SMART,listOf(profile),profile.id))
    }
    @Test fun siteInputNormalizesUrlWildcardAndIdn(){
        assertEquals("example.com",RussianBypass.site(" https://EXAMPLE.com:443/path?q=1#anchor "))
        assertEquals("example.com",RussianBypass.site("*.example.com/"))
        assertEquals("xn--e1afmkfd.xn--p1ai",RussianBypass.site("https://пример.рф/path"))
        assertEquals("xn--p1ai",RussianBypass.site("рф"))
    }
    @Test fun invalidSitesNeverBecomeBroadBypass(){
        listOf("",".","https://","https://user:secret@example.com","example.com/path","*.com/evil","a..ru","foo bar","https://foo@пример.рф/path","http://[::1]","https://example.com:broken").forEach{value->bad{RussianBypass.site(value)}}
    }
    @Test fun defaultBypassIsOff(){assertTrue(RussianBypass.domains(Preferences()).isEmpty());assertTrue(RussianBypass.packages(Preferences()).isEmpty())}
    @Test fun separateSiteAndAppGroupSelection(){
        val p=Preferences(bypassSiteGroups=setOf("vk"),bypassAppGroups=setOf("banks"),directSites=setOf("my.example"))
        assertTrue("vk.com" in RussianBypass.domains(p));assertTrue("my.example" in RussianBypass.domains(p));assertFalse("sber.ru" in RussianBypass.domains(p))
        assertTrue("ru.sberbankmobile" in RussianBypass.packages(p));assertFalse("com.vkontakte.android" in RussianBypass.packages(p))
        assertFalse("com.yandex.browser" in RussianBypass.packages(p.copy(bypassAppGroups=RussianBypass.ids)))
    }
    @Test fun installedAppFilteringAndIncludePrecedence(){
        val installed=setOf("ru.sberbankmobile","com.example.app","com.rayren.android")
        val p=Preferences(appMode=AppMode.INCLUDE,packages=installed+"missing.app",bypassAppGroups=setOf("banks"))
        assertEquals(setOf("com.example.app"),RussianBypass.includedPackages(p,installed,"com.rayren.android"))
        assertEquals(setOf("ru.sberbankmobile","com.rayren.android"),RussianBypass.excludedPackages(p.copy(appMode=AppMode.ALL),installed,"com.rayren.android"))
        assertEquals(installed,RussianBypass.excludedPackages(p.copy(appMode=AppMode.EXCLUDE),installed,"com.rayren.android"))
    }
    @Test fun invalidBypassSettingsRejected(){
        bad{PolicyCompiler.validate(Preferences(bypassSiteGroups=setOf("typo")))}
        bad{PolicyCompiler.validate(Preferences(bypassAppGroups=setOf("typo")))}
        bad{PolicyCompiler.validate(Preferences(directSites=setOf("https://example.com")))}
        bad{PolicyCompiler.validate(Preferences(directSites=(1..129).map{"site$it.example"}.toSet()))}
    }
    @Test fun bypassPolicyPreservesSafetyAndExplicitRulePriority(){
        val p=Preferences(bypassRuDomains=true,bypassSiteGroups=setOf("vk","banks"),directSites=setOf("my.example"),rules=listOf(Rule(RuleKind.SUFFIX,"vk.com",Target.BLOCK)))
        val json=PolicyCompiler.compile(profile,p,Listener(10808,10809,10810,"testuser","testpassword",10811))
        val rules=Json.parse(json).asJsonObject.getAsJsonObject("routing").getAsJsonArray("rules").map{it.asJsonObject}
        val bypass=rules.indexOfFirst{it.text("outboundTag")=="direct"&&it.has("domain")}
        assertTrue(bypass>rules.indexOfFirst{it.text("outboundTag")=="block"&&it.has("domain")})
        assertTrue(bypass>rules.indexOfFirst{it.text("outboundTag")=="dns-out"})
        assertEquals("proxy",rules.first().text("outboundTag"));assertEquals("proxy",rules.last().text("outboundTag"))
        assertTrue(rules[bypass].getAsJsonArray("domain").map{it.asString}.containsAll(listOf("domain:ru","domain:xn--p1ai","domain:vk.com","domain:my.example")))
        File("build/native-fixtures/bypass-groups.json").apply{parentFile.mkdirs();writeText(json)}
    }
}
