package tr.erdvyn.launcher;

import java.util.*;
import java.util.regex.*;

/** Translates only registered launcher-owned messages; never use on player/news content. */
final class UiMessages {
    private final Map<String,String> toTr=new HashMap<>(),toEn=new HashMap<>();
    private final Map<String,String> cache=new HashMap<>();
    UiMessages(){
        String[][] rows={
            {"INITIALIZING ERDVYN RUNTIME","ERDVYN ÇALIŞMA ORTAMI HAZIRLANIYOR"},
            {"JAVA 21 RUNTIME ............... OK","JAVA 21 ORTAMI ............... TAMAM"},
            {"MINECRAFT 1.21.1 .............. FOUND","MINECRAFT 1.21.1 .............. BULUNDU"},
            {"NEOFORGE 21.1.243 ............. READY","NEOFORGE 21.1.243 ............. HAZIR"},
            {"VERIFYING PACKAGE INDEX","PAKET DİZİNİ DOĞRULANIYOR"},{"mod manifest","mod dosya listesi"},{"configs","yapılandırmalar"},{"resources","kaynaklar"},
            {"MOUNTING RESOURCE PACKS","KAYNAK PAKETLERİ YÜKLENİYOR"},{"PREPARING JVM ARGUMENTS","JVM SEÇENEKLERİ HAZIRLANIYOR"},
            {"SYNCHRONIZING LOCAL PROFILE","YEREL PROFİL EŞİTLENİYOR"},{"REGISTERING LAUNCH SERVICES","BAŞLATMA SERVİSLERİ HAZIRLANIYOR"},
            {"PREPARING GAME INSTANCE","OYUN KURULUMU HAZIRLANIYOR"},{"game process","oyun işlemi"},{"SPAWNING GAME PROCESS","OYUN İŞLEMİ BAŞLATILIYOR"},{"HANDOFF TO MINECRAFT","MINECRAFT'A GEÇİLİYOR"},
            {"POWERING ERDVYN CONTROL TERMINAL","ERDVYN KONTROL TERMİNALİ AÇILIYOR"},{"MEMORY MAP .................... OK","BELLEK HARİTASI ............... TAMAM"},
            {"CRT PHOSPHOR LAYER ............ READY","CRT FOSFOR KATMANI ............ HAZIR"},{"LOADING PIXEL GLYPH ROM","PİKSEL YAZI TİPİ YÜKLENİYOR"},
            {"amber color table","kehribar renk tablosu"},{"MOUNTING USER PREFERENCES","KULLANICI TERCİHLERİ YÜKLENİYOR"},{"INITIALIZING VIDEO BUS","GÖRÜNTÜ HATTI HAZIRLANIYOR"},
            {"camera playlist / 07","kamera oynatma listesi / 07"},{"INITIALIZING AUDIO DEVICE","SES AYGITI HAZIRLANIYOR"},{"mechanical UI channel","mekanik arayüz ses kanalı"},
            {"STARTING NETWORK MONITOR","AĞ İZLEYİCİ BAŞLATILIYOR"},{"REGISTERING PANEL MODULES","PANEL MODÜLLERİ HAZIRLANIYOR"},{"SYNCHRONIZING SYSTEM CLOCK","SİSTEM SAATİ EŞİTLENİYOR"},{"UI BUS HANDOFF","ARAYÜZE GEÇİLİYOR"},
            {"PACKAGE VERIFIED","PAKET DOĞRULANDI"},{"PACKAGE HAS %d ERRORS","PAKETTE %d HATA VAR"},
            {"Erdvyn hub connected.","Erdvyn sohbet bağlantısı kuruldu."},{"Hub disconnected.","Sohbet bağlantısı kesildi."},
            {"MANIFEST  ","DOSYA LİSTESİ  "},{"PROCESS STARTED / PID ","İŞLEM BAŞLATILDI / PID "},
            {"AWAITING MINECRAFT","MINECRAFT BEKLENİYOR"},{"MINECRAFT READY","MINECRAFT HAZIR"},{"MINECRAFT PROCESS STABLE","MINECRAFT İŞLEMİ KARARLI"},
            {"MINECRAFT 1.21.1 / NEOFORGE 21.1.243 READY","MINECRAFT 1.21.1 / NEOFORGE 21.1.243 HAZIR"},
            {"INSTALLING MINECRAFT + NEOFORGE","MINECRAFT + NEOFORGE KURULUYOR"},{"INSTALLATION COMPLETE","KURULUM TAMAMLANDI"},
            {"DOWNLOADING NEOFORGE INSTALLER","NEOFORGE KURUCUSU İNDİRİLİYOR"},{"REPAIRING MINECRAFT METADATA","MINECRAFT SÜRÜM BİLGİSİ ONARILIYOR"},
            {"DOWNLOADING MINECRAFT CLIENT","MINECRAFT İSTEMCİSİ İNDİRİLİYOR"},{"DOWNLOADING MINECRAFT METADATA","MINECRAFT SÜRÜM BİLGİSİ İNDİRİLİYOR"},
            {"RETRYING NEOFORGE INSTALLATION ","NEOFORGE KURULUMU YENİDEN DENENİYOR "},
            {"MODLAUNCHER HANDSHAKE / ACCEPTED","MODLAUNCHER BAĞLANTISI / ONAYLANDI"},{"NEOFORGE RUNTIME / ONLINE","NEOFORGE ORTAMI / HAZIR"},
            {"ACCOUNT SESSION / BOUND","HESAP OTURUMU / BAĞLANDI"},{"RENDER BACKEND / LINKED","GÖRÜNTÜ ALTYAPISI / BAĞLANDI"},
            {"RESOURCE BUS / COMPILING ASSETS","KAYNAK HATTI / VARLIKLAR HAZIRLANIYOR"},{"AUDIO BUS / ONLINE","SES HATTI / HAZIR"},
            {"TEXTURE ATLAS / COMPILED","DOKU ATLASI / HAZIR"},{"MINECRAFT READY / RENDER HANDOFF","MINECRAFT HAZIR / GÖRÜNTÜ DEVRALINDI"},
            {"MINECRAFT PROCESS STABLE / HANDOFF","MINECRAFT İŞLEMİ KARARLI / DEVREDİLDİ"},{"NATIVES READY","YEREL KÜTÜPHANELER HAZIR"},
            {"AWAITING MINECRAFT / PID %d / %dS","MINECRAFT BEKLENİYOR / PID %d / %dSN"},
            {"GAME  ","OYUN  "},{"AUTH  ","HESAP  "},{"JOIN  ","BAĞLAN  "},{"CP    %d libraries","CP    %d kütüphane"}
        };for(var row:rows)choose(false,row[1],row[0]);
    }
    synchronized String choose(boolean turkish,String tr,String en){
        if(!tr.equals(en)&&!tr.equals(toTr.get(en))){toTr.put(en,tr);toEn.put(tr,en);cache.clear();}
        return turkish?tr:en;
    }
    synchronized String resolve(boolean turkish,String value){
        if(value==null)return "";
        String key=turkish+value;if(cache.containsKey(key))return cache.get(key);
        String result=resolveUncached(turkish,value);if(cache.size()>512)cache.clear();cache.put(key,result);return result;
    }
    private String resolveUncached(boolean turkish,String value){
        var tag=Pattern.compile("^(\\[[A-Z]+\\] |>? ?)(.+)$").matcher(value);
        if(tag.matches()&&!tag.group(1).isEmpty())return tag.group(1)+resolve(turkish,tag.group(2));
        var entries=new ArrayList<>((turkish?toTr:toEn).entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String,String> e)->e.getKey().length()).reversed());
        for(var e:entries){
            if(value.equals(e.getKey()))return e.getValue();
            // Dynamic suffixes (account names, versions, errors) remain byte-for-byte intact.
            if(e.getKey().length()>5&&e.getKey().endsWith(" ")&&value.startsWith(e.getKey()))return e.getValue()+value.substring(e.getKey().length());
            if(e.getKey().contains("%")){
                var tokens=Pattern.compile("%[ds]").matcher(e.getKey());var pattern=new StringBuilder("^");int pos=0,count=0;
                while(tokens.find()){pattern.append(Pattern.quote(e.getKey().substring(pos,tokens.start()))).append(tokens.group().equals("%d")?"(\\d+)":"(.+?)");pos=tokens.end();count++;}
                if(count==0)continue;pattern.append(Pattern.quote(e.getKey().substring(pos))).append('$');
                var match=Pattern.compile(pattern.toString()).matcher(value);if(!match.matches())continue;
                var out=new StringBuilder();var target=Pattern.compile("%[ds]").matcher(e.getValue());pos=0;int group=1;
                while(target.find()){out.append(e.getValue(),pos,target.start()).append(match.group(group++));pos=target.end();}
                return out.append(e.getValue().substring(pos)).toString();
            }
        }
        return value;
    }
    static String upper(String text,boolean turkish){return text.toUpperCase(turkish?Locale.forLanguageTag("tr-TR"):Locale.ENGLISH);}
}
