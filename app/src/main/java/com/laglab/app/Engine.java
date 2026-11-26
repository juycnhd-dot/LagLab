package com.laglab.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Userspace IPv4 UDP/TCP forwarder with delay, loss and block. One thread owns all state. */
public class Engine implements Runnable {
    public interface Sink { void write(byte[] packet); }

    public volatile int delayMs, lossPercent;
    public volatile boolean block;

    private static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16, MSS = 1360;
    private final Sink sink;
    private final Selector selector;
    private final Random random = new Random();
    private final ConcurrentLinkedQueue<byte[]> inbox = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<Task> tasks = new PriorityQueue<>();
    private final Map<String, Udp> udps = new HashMap<>();
    private final Map<String, Tcp> tcps = new HashMap<>();
    private final ByteBuffer buf = ByteBuffer.allocate(65535);
    private volatile boolean running = true;
    private long order;

    private static final class Task implements Comparable<Task> {
        final long at, n; final Runnable r;
        Task(long at, long n, Runnable r) { this.at = at; this.n = n; this.r = r; }
        public int compareTo(Task o) { return at != o.at ? Long.compare(at, o.at) : Long.compare(n, o.n); }
    }

    private static final class Udp {
        DatagramChannel ch; byte[] src, dst; int sport, dport; long used; String key;
    }

    private static final class Tcp {
        SocketChannel ch; SelectionKey sk; byte[] src, dst; int sport, dport; String key;
        int recvNext, sendNext, sendUna, window = 65535;
        boolean connected, established, appClosed, serverClosed, dead;
        final ArrayDeque<ByteBuffer> out = new ArrayDeque<>();   // app -> server
        int outBytes;
        final ArrayDeque<Seg> unacked = new ArrayDeque<>();      // server -> app
        long used, sentAt;
    }

    private static final class Seg {
        final int seq, flags; final byte[] data;
        Seg(int seq, int flags, byte[] data) { this.seq = seq; this.flags = flags; this.data = data; }
        int end() { return seq + data.length + ((flags & (SYN | FIN)) != 0 ? 1 : 0); }
    }

    public Engine(Sink sink) throws IOException { this.sink = sink; selector = Selector.open(); }

    /** Called from the TUN reader thread. */
    public void input(byte[] packet) { inbox.add(packet); selector.wakeup(); }

    public void stop() { running = false; selector.wakeup(); }

    private static long now() { return System.nanoTime() / 1_000_000; }

    private void later(long ms, Runnable r) { tasks.add(new Task(now() + ms, order++, r)); }

    /** Applies the impairment to one packet, in either direction. Delay is per direction. */
    private void impair(Runnable r) {
        if (block || random.nextInt(100) < lossPercent) return;
        later(delayMs, r);
    }

    public void run() {
        long nextSweep = now() + 500;
        try {
            while (running) {
                long wait = tasks.isEmpty() ? 200 : Math.min(200, tasks.peek().at - now());
                if (!inbox.isEmpty() || wait <= 0) selector.selectNow(); else selector.select(wait);
                byte[] p;
                while ((p = inbox.poll()) != null) {
                    final byte[] packet = p;
                    impair(() -> handle(packet));
                }
                for (SelectionKey k : selector.selectedKeys()) {
                    if (!k.isValid()) continue;
                    if (k.attachment() instanceof Udp) udpRead((Udp) k.attachment());
                    else tcpReady((Tcp) k.attachment(), k);
                }
                selector.selectedKeys().clear();
                while (!tasks.isEmpty() && tasks.peek().at <= now()) tasks.poll().r.run();
                if (now() >= nextSweep) { sweep(); nextSweep = now() + 500; }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            for (SelectionKey k : selector.keys()) close(k.channel());
            close(selector);
        }
    }

    private static void close(AutoCloseable c) { try { c.close(); } catch (Exception ignored) { } }

    private void handle(byte[] p) {
        if (p.length < 28 || (p[0] >> 4) != 4) return;
        int ihl = (p[0] & 15) * 4, total = u16(p, 2);
        if (ihl < 20 || total > p.length || total < ihl + 8 || (u16(p, 6) & 0x3fff) != 0) return; // no fragments
        byte[] src = Arrays.copyOfRange(p, 12, 16), dst = Arrays.copyOfRange(p, 16, 20);
        int sport = u16(p, ihl), dport = u16(p, ihl + 2);
        String key = sport + ">" + Arrays.toString(dst) + ":" + dport;
        try {
            if (p[9] == 17) udp(p, ihl, total, key, src, dst, sport, dport);
            else if (p[9] == 6 && total >= ihl + 20) tcp(p, ihl, total, key, src, dst, sport, dport);
        } catch (IOException | RuntimeException e) {
            Tcp t = tcps.get(key);
            if (t != null) reset(t);
        }
    }

    // ---- UDP ----
    private void udp(byte[] p, int ihl, int total, String key, byte[] src, byte[] dst, int sport, int dport) throws IOException {
        Udp u = udps.get(key);
        if (u == null) {
            DatagramChannel ch = DatagramChannel.open();
            try {
                ch.configureBlocking(false);
                ch.connect(new InetSocketAddress(InetAddress.getByAddress(dst), dport));
                u = new Udp();
                u.ch = ch; u.src = src; u.dst = dst; u.sport = sport; u.dport = dport; u.key = key;
                ch.register(selector, SelectionKey.OP_READ, u);
            } catch (IOException e) { close(ch); throw e; }
            udps.put(key, u);
        }
        u.used = now();
        u.ch.write(ByteBuffer.wrap(p, ihl + 8, total - ihl - 8));
    }

    private void udpRead(Udp u) {
        try {
            buf.clear();
            if (u.ch.read(buf) < 0) return;
            u.used = now();
            int n = buf.position();
            byte[] r = ip(17, u.dst, u.src, 8 + n);
            put16(r, 20, u.dport); put16(r, 22, u.sport); put16(r, 24, 8 + n);
            System.arraycopy(buf.array(), 0, r, 28, n);
            put16(r, 26, transportSum(r));
            impair(() -> sink.write(r));
        } catch (IOException e) { udps.remove(u.key); close(u.ch); }
    }

    // ---- TCP ----
    private void tcp(byte[] p, int ihl, int total, String key, byte[] src, byte[] dst, int sport, int dport) throws IOException {
        int seq = u32(p, ihl + 4), ack = u32(p, ihl + 8), off = ((p[ihl + 12] >> 4) & 15) * 4;
        int flags = p[ihl + 13] & 63, len = total - ihl - off;
        if (len < 0) return;
        Tcp t = tcps.get(key);
        if ((flags & RST) != 0) { if (t != null) drop(t); return; }
        if (t == null) {
            if ((flags & SYN) == 0) { // unknown connection: tell the app to give up
                byte[] r = segment(dst, src, dport, sport, ack, seq + len, RST | ACK, new byte[0]);
                impair(() -> sink.write(r));
                return;
            }
            t = new Tcp();
            t.src = src; t.dst = dst; t.sport = sport; t.dport = dport; t.key = key;
            t.recvNext = seq + 1; t.sendNext = t.sendUna = random.nextInt(); t.used = now();
            t.ch = SocketChannel.open();
            tcps.put(key, t);
            t.ch.configureBlocking(false);
            t.sk = t.ch.register(selector, SelectionKey.OP_CONNECT, t);
            if (t.ch.connect(new InetSocketAddress(InetAddress.getByAddress(dst), dport))) connected(t);
            return;
        }
        t.used = now();
        if ((flags & SYN) != 0) return; // duplicate SYN; SYN-ACK is retransmitted by sweep
        t.window = u16(p, ihl + 14);
        if ((flags & ACK) != 0 && ack - t.sendUna > 0 && ack - t.sendNext <= 0) {
            t.sendUna = ack; t.sentAt = now(); t.established = true;
            while (!t.unacked.isEmpty() && t.unacked.peek().end() - ack <= 0) t.unacked.poll();
        }
        if (len > 0 || (flags & FIN) != 0) {
            if (seq == t.recvNext && !t.appClosed && t.outBytes < 262144) {
                if (len > 0) {
                    t.out.add(ByteBuffer.wrap(Arrays.copyOfRange(p, ihl + off, total)));
                    t.outBytes += len; t.recvNext += len;
                }
                if ((flags & FIN) != 0) { t.recvNext++; t.appClosed = true; }
            }
            reply(t, ACK);
        }
        if (t.connected) flush(t);
        update(t);
    }

    private void connected(Tcp t) {
        t.connected = true;
        send(t, SYN | ACK, new byte[0]);
        update(t);
    }

    private void tcpReady(Tcp t, SelectionKey k) {
        try {
            if (k.isConnectable() && t.ch.finishConnect()) { connected(t); return; }
            if (k.isValid() && k.isWritable()) flush(t);
            if (k.isValid() && k.isReadable()) {
                int room = Math.min(MSS, t.window - (t.sendNext - t.sendUna));
                if (room > 0) {
                    buf.clear().limit(room);
                    int n = t.ch.read(buf);
                    if (n > 0) send(t, PSH | ACK, Arrays.copyOf(buf.array(), n));
                    else if (n < 0) { t.serverClosed = true; send(t, FIN | ACK, new byte[0]); }
                    t.used = now();
                }
            }
            update(t);
        } catch (IOException | RuntimeException e) { reset(t); }
    }

    private void flush(Tcp t) throws IOException {
        while (!t.out.isEmpty()) {
            ByteBuffer b = t.out.peek();
            t.outBytes -= t.ch.write(b);
            if (b.hasRemaining()) return;
            t.out.poll();
        }
        if (t.appClosed && t.ch.isConnected()) t.ch.shutdownOutput();
    }

    private void update(Tcp t) {
        if (t.dead || !t.sk.isValid() || !t.connected) return;
        int ops = t.out.isEmpty() ? 0 : SelectionKey.OP_WRITE;
        if (t.established && !t.serverClosed && t.unacked.size() < 48 && t.window > t.sendNext - t.sendUna)
            ops |= SelectionKey.OP_READ;
        t.sk.interestOps(ops);
        if (t.appClosed && t.serverClosed && t.unacked.isEmpty() && t.out.isEmpty()) drop(t);
    }

    private void send(Tcp t, int flags, byte[] data) {
        Seg s = new Seg(t.sendNext, flags, data);
        t.sendNext = s.end();
        if (t.unacked.isEmpty()) t.sentAt = now();
        t.unacked.add(s);
        emit(t, s);
    }

    private void reply(Tcp t, int flags) { emit(t, new Seg(t.sendNext, flags, new byte[0])); }

    private void emit(Tcp t, Seg s) {
        byte[] r = segment(t.dst, t.src, t.dport, t.sport, s.seq, t.recvNext, s.flags, s.data);
        impair(() -> sink.write(r));
    }

    private void drop(Tcp t) { t.dead = true; tcps.remove(t.key); close(t.ch); }

    private void reset(Tcp t) { reply(t, RST | ACK); drop(t); }

    private void sweep() {
        long n = now(), rto = 600 + 2L * delayMs;
        for (Tcp t : new ArrayList<>(tcps.values())) {
            if (n - t.used > (t.connected ? 600_000 : 30_000)) { reset(t); continue; }
            if (!t.unacked.isEmpty() && n - t.sentAt > rto) { // go-back-N
                t.sentAt = n;
                int count = 0;
                for (Seg s : t.unacked) { emit(t, s); if (++count == 16) break; }
            }
            update(t);
        }
        for (Udp u : new ArrayList<>(udps.values()))
            if (n - u.used > 60_000) { udps.remove(u.key); close(u.ch); }
    }

    // ---- packet building ----
    static int u16(byte[] b, int i) { return (b[i] & 255) << 8 | (b[i + 1] & 255); }
    static int u32(byte[] b, int i) { return u16(b, i) << 16 | u16(b, i + 2); }
    static void put16(byte[] b, int i, int v) { b[i] = (byte) (v >> 8); b[i + 1] = (byte) v; }
    static void put32(byte[] b, int i, int v) { put16(b, i, v >>> 16); put16(b, i + 2, v); }

    static int sum(byte[] b, int from, int to, long s) {
        for (int i = from; i < to; i += 2) s += (b[i] & 255) << 8 | (i + 1 < to ? b[i + 1] & 255 : 0);
        while (s >>> 16 != 0) s = (s & 0xffff) + (s >>> 16);
        return (int) (~s & 0xffff);
    }

    static int transportSum(byte[] p) {
        long pseudo = p[9] + p.length - 20;
        for (int i = 12; i < 20; i += 2) pseudo += u16(p, i);
        return sum(p, 20, p.length, pseudo);
    }

    static byte[] ip(int proto, byte[] src, byte[] dst, int payload) {
        byte[] p = new byte[20 + payload];
        p[0] = 0x45; put16(p, 2, p.length); put16(p, 6, 0x4000); p[8] = 64; p[9] = (byte) proto;
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4);
        put16(p, 10, sum(p, 0, 20, 0));
        return p;
    }

    static byte[] segment(byte[] src, byte[] dst, int sport, int dport, int seq, int ack, int flags, byte[] data) {
        int h = (flags & SYN) != 0 ? 24 : 20;
        byte[] p = ip(6, src, dst, h + data.length);
        put16(p, 20, sport); put16(p, 22, dport); put32(p, 24, seq); put32(p, 28, ack);
        p[32] = (byte) (h / 4 << 4); p[33] = (byte) flags; put16(p, 34, 65535);
        if (h == 24) { p[40] = 2; p[41] = 4; put16(p, 42, MSS); }
        System.arraycopy(data, 0, p, 20 + h, data.length);
        put16(p, 36, transportSum(p));
        return p;
    }
}
