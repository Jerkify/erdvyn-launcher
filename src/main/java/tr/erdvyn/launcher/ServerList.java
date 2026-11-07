package tr.erdvyn.launcher;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the Erdvyn server in the instance's multiplayer list (servers.dat, uncompressed NBT). Entries the player added
 * stay as they are; the server goes first when it is missing. A file that does not parse is left alone.
 */
final class ServerList {
    private ServerList() {}

    /** Adds {@code address} as {@code name} to {@code game}/servers.dat unless an entry already points at it. */
    static boolean ensure(Path game, String name, String address) throws IOException {
        Path file = game.resolve("servers.dat");
        Map<String, Object> root;
        if (Files.isRegularFile(file)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readByte() != COMPOUND) return false;
                in.readUTF();
                root = compound(in);
            } catch (IOException | RuntimeException e) {
                return false; // not ours to repair
            }
        } else {
            root = new LinkedHashMap<>();
        }
        Object found = root.get("servers");
        NbtList servers = found instanceof NbtList list && (list.type == COMPOUND || list.items.isEmpty()) ? list : new NbtList(COMPOUND, new ArrayList<>());
        for (Object o : servers.items) if (o instanceof Map<?, ?> entry && same(entry.get("ip"), address)) return false;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("ip", address);
        servers.items.add(0, entry);
        root.put("servers", new NbtList(COMPOUND, servers.items));
        Files.createDirectories(game);
        Path temp = game.resolve("servers.dat.erdvyn-tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeByte(COMPOUND);
            out.writeUTF("");
            writeCompound(out, root);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /** The same server: case-insensitive, the default port optional. */
    private static boolean same(Object ip, String address) {
        if (!(ip instanceof String s)) return false;
        return strip(s).equals(strip(address));
    }
    private static String strip(String a) {
        String s = a.trim().toLowerCase(Locale.ROOT);
        return s.endsWith(":25565") ? s.substring(0, s.length() - 6) : s;
    }

    // ---------------------------------------------------------------- NBT, just enough to round-trip any file

    private static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTES = 7, STRING = 8, LIST = 9, COMPOUND = 10, INTS = 11, LONGS = 12;

    private record NbtList(byte type, List<Object> items) {}
    /** Remembers each primitive's tag type so it is written back the same. */
    private record Tagged(byte type, Object value) {}

    private static Map<String, Object> compound(DataInputStream in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        for (byte type; (type = in.readByte()) != END; ) {
            String key = in.readUTF();
            map.put(key, read(in, type));
        }
        return map;
    }

    private static Object read(DataInputStream in, byte type) throws IOException {
        return switch (type) {
            case BYTE -> new Tagged(type, in.readByte());
            case SHORT -> new Tagged(type, in.readShort());
            case INT -> new Tagged(type, in.readInt());
            case LONG -> new Tagged(type, in.readLong());
            case FLOAT -> new Tagged(type, in.readFloat());
            case DOUBLE -> new Tagged(type, in.readDouble());
            case BYTES -> { byte[] a = new byte[length(in)]; in.readFully(a); yield new Tagged(type, a); }
            case STRING -> in.readUTF();
            case LIST -> {
                byte element = in.readByte();
                int n = length(in);
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) items.add(read(in, element));
                yield new NbtList(element, items);
            }
            case COMPOUND -> compound(in);
            case INTS -> { int[] a = new int[length(in)]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); yield new Tagged(type, a); }
            case LONGS -> { long[] a = new long[length(in)]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); yield new Tagged(type, a); }
            default -> throw new IOException("unknown NBT tag " + type);
        };
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 1 << 20) throw new IOException("bad NBT length " + n);
        return n;
    }

    private static byte typeOf(Object v) {
        if (v instanceof Tagged t) return t.type();
        if (v instanceof String) return STRING;
        if (v instanceof NbtList) return LIST;
        if (v instanceof Map) return COMPOUND;
        throw new IllegalArgumentException(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static void writeCompound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (var e : map.entrySet()) {
            out.writeByte(typeOf(e.getValue()));
            out.writeUTF(e.getKey());
            write(out, e.getValue());
        }
        out.writeByte(END);
    }

    @SuppressWarnings("unchecked")
    private static void write(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case String s -> out.writeUTF(s);
            case Map<?, ?> m -> writeCompound(out, (Map<String, Object>) m);
            case NbtList l -> {
                out.writeByte(l.items().isEmpty() ? END : l.type());
                out.writeInt(l.items().size());
                for (Object o : l.items()) write(out, o);
            }
            case Tagged t -> {
                switch (t.type()) {
                    case BYTE -> out.writeByte((Byte) t.value());
                    case SHORT -> out.writeShort((Short) t.value());
                    case INT -> out.writeInt((Integer) t.value());
                    case LONG -> out.writeLong((Long) t.value());
                    case FLOAT -> out.writeFloat((Float) t.value());
                    case DOUBLE -> out.writeDouble((Double) t.value());
                    case BYTES -> { byte[] a = (byte[]) t.value(); out.writeInt(a.length); out.write(a); }
                    case INTS -> { int[] a = (int[]) t.value(); out.writeInt(a.length); for (int i : a) out.writeInt(i); }
                    case LONGS -> { long[] a = (long[]) t.value(); out.writeInt(a.length); for (long l : a) out.writeLong(l); }
                    default -> throw new IOException("unknown NBT tag " + t.type());
                }
            }
            default -> throw new IOException("cannot write " + v);
        }
    }

    /** Self-check in a scratch directory: a player's own entry survives, Erdvyn goes first, once. */
    static void selfCheck(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve("servers.dat"));
        if (!ensure(dir, "Mine", "other.example:25570")) throw new AssertionError("fresh file not written");
        if (!ensure(dir, "Erdvyn", "play.erdvyn.net")) throw new AssertionError("not added to an existing list");
        if (ensure(dir, "Erdvyn", "PLAY.erdvyn.net:25565")) throw new AssertionError("duplicate added");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(dir.resolve("servers.dat")))) {
            if (in.readByte() != COMPOUND) throw new AssertionError("root");
            in.readUTF();
            NbtList list = (NbtList) compound(in).get("servers");
            if (list.items().size() != 2 || !"play.erdvyn.net".equals(((Map<?, ?>) list.items().get(0)).get("ip"))
                    || !"other.example:25570".equals(((Map<?, ?>) list.items().get(1)).get("ip"))) throw new AssertionError("content " + list);
        }
    }
}
