package tr.erdvyn.launcher;

import java.util.*;
import java.util.regex.*;

/** What to do about common launch and pack failures. The launcher is English only since 2026-11-07 (no translations). */
final class UiMessages {
    private UiMessages() {}

    /** What-to-do text for common launch/pack failures, or null. Callers keep the raw error in the trace and LauncherLog. */
    static String hint(Throwable error){
        StringBuilder text=new StringBuilder();boolean network=false,denied=false;
        for(Throwable t=error;t!=null;t=t.getCause()==t?null:t.getCause()){
            text.append(' ').append(t.getMessage());
            network|=t instanceof java.net.UnknownHostException||t instanceof java.net.SocketException||t instanceof java.net.SocketTimeoutException||t instanceof java.net.http.HttpTimeoutException||t instanceof javax.net.ssl.SSLException||t instanceof java.nio.channels.UnresolvedAddressException;
            denied|=t instanceof java.nio.file.AccessDeniedException;
        }
        String s=text.toString().toLowerCase(Locale.ROOT);
        if(s.contains("java 21 runtime was not found")||s.contains("java 21 is required"))return "Java 21 was not found. Reinstall the launcher or install 64-bit Java 21.";
        if(s.contains("does not own minecraft")||s.contains("no java profile")||s.contains("entitlement")||s.contains("minecraft profile"))return "This Microsoft account has no Minecraft: Java Edition profile. Create one at minecraft.net or sign in with the right account.";
        if(s.contains("login timed out")||s.contains("expired_token"))return "Sign-in timed out. Press SIGN IN again and confirm the code within 5 minutes.";
        if(s.contains("authorization_declined")||s.contains("access_denied"))return "Sign-in was declined in the browser. Try again and allow access.";
        if(s.contains("not enough space")||s.contains("no space left")||s.contains("disk full")||s.contains("disk is full"))return "The disk is full. Free up some space and try again.";
        if(denied||s.contains("access is denied")||s.contains("being used by another process"))return "A file could not be accessed. Close any running Minecraft; antivirus may be blocking the folder.";
        if(s.contains("sha-256 mismatch")||s.contains("hash mismatch")||s.contains("checksum mismatch"))return "A downloaded file failed its SHA-256 check. Try again; if it keeps failing, tell an admin.";
        if(Pattern.compile("http 5\\d\\d").matcher(s).find())return "The download server is having trouble (HTTP 5xx). Try again in a few minutes.";
        if(network)return "Could not reach the server. Check your internet connection and try again.";
        return null;
    }
}
