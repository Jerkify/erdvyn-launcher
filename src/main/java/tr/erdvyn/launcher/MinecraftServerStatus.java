package tr.erdvyn.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class MinecraftServerStatus implements AutoCloseable {
    record Snapshot(boolean online, int players, int maxPlayers, long latencyMs, String version, String description, List<String> sample) {
        static Snapshot offline() { return new Snapshot(false, 0, 0, -1, "--", "", List.of()); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Consumer<Snapshot> callback;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "erdvyn-server-status");thread.setDaemon(true);return thread;
    });

    MinecraftServerStatus(Consumer<Snapshot> callback) { this.callback = callback; }

    void start() { scheduler.scheduleWithFixedDelay(this::refresh, 0, 12, TimeUnit.SECONDS); }

    private void refresh() {
        try { callback.accept(ping()); } catch (Exception ignored) { callback.accept(Snapshot.offline()); }
    }

    private Snapshot ping() throws Exception {
        String address = LauncherConfig.statusAddress();
        int split = address.lastIndexOf(':');
        String host = split > 0 ? address.substring(0, split) : address;
        int port = split > 0 ? Integer.parseInt(address.substring(split + 1)) : 25565;
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3500);socket.setSoTimeout(3500);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());DataInputStream input = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();DataOutputStream handshake = new DataOutputStream(handshakeBytes);
            writeVarInt(handshake, 0);writeVarInt(handshake, 767);writeString(handshake, host);handshake.writeShort(port);writeVarInt(handshake, 1);
            writePacket(output, handshakeBytes.toByteArray());writePacket(output, new byte[]{0});
            readVarInt(input);int packetId=readVarInt(input);if(packetId!=0)throw new IllegalStateException("Unexpected status packet");
            int length=readVarInt(input);if(length<0||length>2_000_000)throw new IllegalStateException("Invalid status length");byte[] jsonBytes=input.readNBytes(length);if(jsonBytes.length!=length)throw new IllegalStateException("Incomplete status response");
            long latency = Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));JsonNode json=JSON.readTree(jsonBytes);
            int online=json.path("players").path("online").asInt(),max=json.path("players").path("max").asInt();String version=json.path("version").path("name").asText("--");String description=description(json.path("description"));List<String> sample=new ArrayList<>();JsonNode samples=json.path("players").path("sample");if(samples.isArray())samples.forEach(item->{String name=item.path("name").asText();if(!name.isBlank())sample.add(name);});
            return new Snapshot(true,online,max,latency,version,description,List.copyOf(sample));
        }
    }

    private static String description(JsonNode node) { if(node.isTextual())return node.asText();if(node.isObject())return node.path("text").asText();return ""; }
    private static void writePacket(DataOutputStream output,byte[] payload)throws Exception{writeVarInt(output,payload.length);output.write(payload);output.flush();}
    private static void writeString(DataOutputStream output,String value)throws Exception{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);writeVarInt(output,bytes.length);output.write(bytes);}
    private static void writeVarInt(DataOutputStream output,int value)throws Exception{while((value&-128)!=0){output.writeByte(value&127|128);value>>>=7;}output.writeByte(value);}
    private static int readVarInt(DataInputStream input)throws Exception{int value=0,position=0,current;do{current=input.readUnsignedByte();value|=(current&127)<<position;if((position+=7)>=35)throw new IllegalStateException("VarInt too large");}while((current&128)!=0);return value;}
    @Override public void close(){scheduler.shutdownNow();}
}
