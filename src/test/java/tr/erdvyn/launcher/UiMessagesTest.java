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
    @Test void actionableHints(){
        assertTrue(UiMessages.hint(new java.io.IOException("Java 21 runtime was not found"))[1].startsWith("Java 21 was not found"));
        assertTrue(UiMessages.hint(new IllegalStateException("Modpack verification failed",new java.net.UnknownHostException("api.erdvyn.net")))[1].startsWith("Could not reach"));
        assertTrue(UiMessages.hint(new java.io.IOException("Manifest HTTP 503"))[1].contains("HTTP 5xx"));
        assertTrue(UiMessages.hint(new java.io.IOException("SHA-256 mismatch"))[1].contains("SHA-256"));
        assertTrue(UiMessages.hint(new java.io.IOException("There is not enough space on the disk"))[1].contains("disk is full"));
        assertTrue(UiMessages.hint(new java.nio.file.AccessDeniedException("mods/a.jar"))[1].contains("could not be accessed"));
        assertTrue(UiMessages.hint(new java.io.IOException("Microsoft sign-in succeeded, but this account does not own Minecraft: Java Edition or has no Java profile"))[1].contains("no Minecraft: Java Edition profile"));
        assertNull(UiMessages.hint(new java.io.IOException("HTTP 404")));
        assertNull(UiMessages.hint(null));
    }
}
