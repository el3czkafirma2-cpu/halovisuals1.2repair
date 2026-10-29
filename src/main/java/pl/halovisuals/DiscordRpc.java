package pl.halovisuals;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Minimal Discord Rich Presence client over Discord's local IPC (named pipe on Windows, unix socket elsewhere).
 * No external libraries. Needs a Discord application id (config key "_discordAppId").
 * Art assets (keys "halo_logo" and "halo_small") are uploaded in the Discord developer portal under Rich Presence -> Art Assets.
 */
public final class DiscordRpc {
    private static InputStream in;
    private static OutputStream out;
    private static Closeable closer;
    private static boolean connected;
    private static final long START = System.currentTimeMillis() / 1000L;

    private DiscordRpc() {}

    public static void init() {
        Thread t = new Thread(DiscordRpc::loop, "HaloVisuals-Discord");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        long lastTry = 0, lastSend = 0;
        while (true) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            long now = System.currentTimeMillis();
            Module m = ModuleManager.get("Discord Presence");
            boolean want = m != null && m.enabled && !ModuleManager.discordAppId.isBlank();
            try {
                if (want) {
                    if (!connected && now - lastTry > 10000) { lastTry = now; connect(ModuleManager.discordAppId.trim()); lastSend = 0; }
                    if (connected && now - lastSend > 15000) { lastSend = now; send(activity(m)); }
                } else if (connected) {
                    send(null);
                    close();
                }
            } catch (Throwable ex) {
                close();
            }
        }
    }

    private static JsonObject activity(Module m) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String state;
        if (mc.world == null) state = "In the menus";
        else if (mc.isInSingleplayer()) state = "Playing singleplayer";
        else {
            ServerInfo si = mc.getCurrentServerEntry();
            state = (m.on("ShowServer") && si != null) ? "Playing on " + si.address : "Playing multiplayer";
        }
        JsonObject a = new JsonObject();
        a.addProperty("details", "Halo Visuals - Angelic Client");
        a.addProperty("state", state);
        JsonObject ts = new JsonObject();
        ts.addProperty("start", START);
        a.add("timestamps", ts);
        JsonObject as = new JsonObject();
        as.addProperty("large_image", "halo_logo");
        as.addProperty("large_text", "Halo Visuals - Angelic Client");
        as.addProperty("small_image", "halo_small");
        as.addProperty("small_text", "Minecraft 1.21.4");
        a.add("assets", as);
        return a;
    }

    // ------------------------------------------------------------ transport
    private static void connect(String appId) throws IOException {
        close();
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        IOException last = null;
        for (int i = 0; i < 10 && !connected; i++) {
            try {
                if (win) {
                    final RandomAccessFile raf = new RandomAccessFile("\\\\?\\pipe\\discord-ipc-" + i, "rw");
                    in = new InputStream() {
                        @Override public int read() throws IOException { return raf.read(); }
                        @Override public int read(byte[] b, int o, int l) throws IOException { return raf.read(b, o, l); }
                    };
                    out = new OutputStream() {
                        @Override public void write(int b) throws IOException { raf.write(b); }
                        @Override public void write(byte[] b, int o, int l) throws IOException { raf.write(b, o, l); }
                    };
                    closer = raf;
                    connected = true;
                } else {
                    for (String base : unixDirs()) {
                        for (String sub : new String[]{"", "/snap.discord", "/app/com.discordapp.Discord"}) {
                            Path p = Path.of(base + sub, "discord-ipc-" + i);
                            if (!Files.exists(p)) continue;
                            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                            ch.connect(UnixDomainSocketAddress.of(p));
                            in = Channels.newInputStream(ch);
                            out = Channels.newOutputStream(ch);
                            closer = ch;
                            connected = true;
                            break;
                        }
                        if (connected) break;
                    }
                }
            } catch (IOException e) { last = e; connected = false; }
        }
        if (!connected) throw last != null ? last : new IOException("Discord not running");
        JsonObject hs = new JsonObject();
        hs.addProperty("v", 1);
        hs.addProperty("client_id", appId);
        writeFrame(0, hs.toString());
        readFrame(); // READY
    }

    private static String[] unixDirs() {
        String x = System.getenv("XDG_RUNTIME_DIR"), t = System.getenv("TMPDIR"), t2 = System.getenv("TMP"), t3 = System.getenv("TEMP");
        return new String[]{x == null ? "/run/user/1000" : x, t == null ? "/tmp" : t, t2 == null ? "/tmp" : t2, t3 == null ? "/tmp" : t3, "/tmp"};
    }

    private static void send(JsonObject activity) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("cmd", "SET_ACTIVITY");
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", activity == null ? JsonNull.INSTANCE : activity);
        root.add("args", args);
        root.addProperty("nonce", UUID.randomUUID().toString());
        writeFrame(1, root.toString());
        readFrame();
    }

    private static void writeFrame(int op, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(op).putInt(data.length).put(data);
        out.write(bb.array());
        out.flush();
    }

    private static String readFrame() throws IOException {
        byte[] head = readFully(8);
        ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int op = hb.getInt(), len = hb.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad frame");
        String body = new String(readFully(len), StandardCharsets.UTF_8);
        if (op == 2) throw new IOException("closed: " + body);
        return body;
    }

    private static byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    private static void close() {
        connected = false;
        try { if (closer != null) closer.close(); } catch (IOException ignored) {}
        closer = null; in = null; out = null;
    }
}
