package tr.erdvyn.launcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UiMessagesTest {
    @Test void storedNoticeSwitchesBothWays(){var m=new UiMessages();String old=m.choose(true,"Paket hazır.","Pack ready.");assertEquals("Pack ready.",m.resolve(false,old));assertEquals(old,m.resolve(true,"Pack ready."));}
    @Test void formattedVersionSurvives(){var m=new UiMessages();m.choose(true,"Launcher %s indirildi.","Launcher %s downloaded.");assertEquals("Launcher 2.1.22 downloaded.",m.resolve(false,"Launcher 2.1.22 indirildi."));}
    @Test void numbersSurvive(){var m=new UiMessages();assertEquals("PAKETTE 3 HATA VAR",m.resolve(true,"PACKAGE HAS 3 ERRORS"));}
    @Test void taggedBootTranslates(){var m=new UiMessages();assertEquals("> ERDVYN ÇALIŞMA ORTAMI HAZIRLANIYOR",m.resolve(true,"> INITIALIZING ERDVYN RUNTIME"));assertEquals("[OK] yapılandırmalar",m.resolve(true,"[OK] configs"));}
    @Test void suffixIsNotTranslated(){var m=new UiMessages();m.choose(true,"HATA: ","ERROR: ");assertEquals("HATA: C:/Mods/READY.jar",m.resolve(true,"ERROR: C:/Mods/READY.jar"));}
    @Test void unknownContentUnchanged(){var m=new UiMessages();assertEquals("My player message",m.resolve(true,"My player message"));}
    @Test void turkishCase(){assertEquals("İSTEMCİ DİLİ",UiMessages.upper("istemci dili",true));assertEquals("CLIENT",UiMessages.upper("client",false));}
    @Test void mapSwitchesWithoutRefresh(){var map=new LocalSurveyMap(java.nio.file.Path.of("missing-test-map"));map.setTurkish(false);assertTrue(map.status().startsWith("Open the map"));map.setTurkish(true);assertTrue(map.status().startsWith("Yerel"));}
}
