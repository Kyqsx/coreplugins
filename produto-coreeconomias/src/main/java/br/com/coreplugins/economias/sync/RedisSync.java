package br.com.coreplugins.economias.sync;

import org.bukkit.configuration.ConfigurationSection;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Aviso entre servidores via Redis Pub/Sub ("o saldo do jogador X mudou, releia do banco").
 * <p>
 * Redis NÃO guarda saldo: o MySQL continua sendo a única fonte de verdade. Uma mensagem perdida ou
 * forjada, no pior caso, só provoca uma leitura a mais (ou atrasa a atualização até o resync periódico);
 * nunca altera valores. Por isso uma queda do Redis não afeta a economia.
 * <p>
 * Cliente mínimo (AUTH, PUBLISH, SUBSCRIBE, PING) sem dependências, para não precisar embutir o Jedis no jar.
 */
public final class RedisSync {

    public interface Listener {
        /** Outro servidor mudou o saldo deste jogador. Chamado na thread do assinante: não bloqueie. */
        void onRemoteChange(UUID player);

        /** A conexão foi (re)estabelecida: mensagens podem ter sido perdidas, vale reler tudo. */
        void onReconnected();
    }

    private final Logger logger;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final String channel;
    private final int timeoutMs;
    private final Listener listener;
    private final String serverId = UUID.randomUUID().toString();
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<String>(10000);

    private volatile boolean running;
    private volatile Socket subSocket;
    private Thread subscriber;
    private Thread publisher;

    public RedisSync(Logger logger, ConfigurationSection cfg, Listener listener) {
        this.logger = logger;
        this.listener = listener;
        this.host = cfg.getString("host", "localhost");
        this.port = cfg.getInt("port", 6379);
        this.username = cfg.getString("username", "");
        this.password = cfg.getString("password", "");
        this.ssl = cfg.getBoolean("ssl", false);
        this.channel = cfg.getString("channel", "coreeconomias");
        this.timeoutMs = Math.max(500, cfg.getInt("timeout-ms", 2000));
    }

    public void start() {
        running = true;
        subscriber = new Thread(new Runnable() {
            @Override
            public void run() {
                subscribeLoop();
            }
        }, "coreEconomias-redis-sub");
        subscriber.setDaemon(true);
        subscriber.start();
        publisher = new Thread(new Runnable() {
            @Override
            public void run() {
                publishLoop();
            }
        }, "coreEconomias-redis-pub");
        publisher.setDaemon(true);
        publisher.start();
    }

    /** Não bloqueia: se o Redis estiver fora, a mensagem é descartada quando a fila enche. */
    public void publish(UUID player) {
        if (running) {
            outbox.offer(serverId + "|" + player);
        }
    }

    public void close() {
        running = false;
        Socket s = subSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // encerrando
            }
        }
        if (subscriber != null) {
            subscriber.interrupt();
        }
        if (publisher != null) {
            publisher.interrupt();
        }
    }

    // ---------- assinante ----------

    private void subscribeLoop() {
        long backoff = 1000L;
        boolean warned = false;
        while (running) {
            Socket s = null;
            try {
                s = connect();
                subSocket = s;
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                s.setSoTimeout(15000);
                send(out, "SUBSCRIBE", channel);
                warned = false;
                backoff = 1000L;
                logger.info("Redis conectado (" + host + ":" + port + "), canal '" + channel + "'.");
                listener.onReconnected();
                while (running) {
                    int first;
                    try {
                        first = in.read();
                    } catch (SocketTimeoutException idle) {
                        send(out, "PING"); // mantém a conexão viva e detecta conexão morta
                        continue;
                    }
                    if (first < 0) {
                        throw new IOException("conexão encerrada pelo Redis");
                    }
                    // Timeout no meio de uma mensagem cai no catch externo e reconecta (sem perder o sincronismo).
                    handle(readReply(in, first));
                }
            } catch (IOException | RuntimeException e) {
                if (running) {
                    if (!warned) {
                        logger.log(Level.WARNING, "Redis indisponível (" + e.getMessage()
                                + "). A economia segue funcionando pelo banco; tentando reconectar.");
                        warned = true;
                    }
                }
            } finally {
                subSocket = null;
                closeQuietly(s);
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                break;
            }
            backoff = Math.min(backoff * 2, 30000L);
        }
    }

    private void handle(Object reply) {
        if (!(reply instanceof List)) {
            return;
        }
        List<?> list = (List<?>) reply;
        if (list.size() < 3 || !"message".equals(list.get(0))) {
            return;
        }
        String payload = String.valueOf(list.get(2));
        int bar = payload.indexOf('|');
        if (bar <= 0) {
            return;
        }
        if (payload.substring(0, bar).equals(serverId)) {
            return; // eco da nossa própria publicação
        }
        try {
            listener.onRemoteChange(UUID.fromString(payload.substring(bar + 1)));
        } catch (IllegalArgumentException ignored) {
            // mensagem malformada: ignora
        }
    }

    // ---------- publicador ----------

    private void publishLoop() {
        Socket s = null;
        InputStream in = null;
        OutputStream out = null;
        boolean warned = false;
        while (running) {
            String msg;
            try {
                msg = outbox.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (msg == null) {
                continue;
            }
            try {
                if (s == null || s.isClosed()) {
                    s = connect();
                    s.setSoTimeout(timeoutMs);
                    in = new BufferedInputStream(s.getInputStream());
                    out = s.getOutputStream();
                }
                send(out, "PUBLISH", channel, msg);
                readReply(in);
                warned = false;
            } catch (IOException | RuntimeException e) {
                closeQuietly(s);
                s = null;
                if (!warned && running) {
                    logger.log(Level.WARNING, "Falha ao publicar no Redis (" + e.getMessage()
                            + "); outros servidores atualizarão pelo resync periódico.");
                    warned = true;
                }
                try {
                    Thread.sleep(1000L); // não martela um Redis fora do ar
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        closeQuietly(s);
    }

    // ---------- protocolo RESP ----------

    private Socket connect() throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.setKeepAlive(true);
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        Socket s = raw;
        if (ssl) {
            s = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
        }
        try {
            s.setSoTimeout(timeoutMs);
            if (password != null && !password.isEmpty()) {
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                if (username != null && !username.isEmpty()) {
                    send(out, "AUTH", username, password);
                } else {
                    send(out, "AUTH", password);
                }
                readReply(in); // erro do servidor vira IOException
            }
            return s;
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw e;
        }
    }

    private static void send(OutputStream out, String... args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            buf.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            buf.write(b);
            buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("conexão encerrada pelo Redis");
            }
            if (b == '\r') {
                int n = in.read();
                if (n == '\n') {
                    return new String(line.toByteArray(), StandardCharsets.UTF_8);
                }
                line.write(b);
                if (n < 0) {
                    throw new IOException("conexão encerrada pelo Redis");
                }
                line.write(n);
            } else {
                line.write(b);
            }
        }
    }

    private static Object readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new IOException("conexão encerrada pelo Redis");
        }
        return readReply(in, type);
    }

    private static Object readReply(InputStream in, int type) throws IOException {
        String line = readLine(in);
        switch (type) {
            case '+':
                return line;
            case '-':
                throw new IOException("Redis: " + line);
            case ':':
                return Long.valueOf(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                if (len > 1024 * 1024) {
                    throw new IOException("resposta grande demais");
                }
                byte[] data = new byte[len];
                int off = 0;
                while (off < len) {
                    int r = in.read(data, off, len - off);
                    if (r < 0) {
                        throw new IOException("conexão encerrada pelo Redis");
                    }
                    off += r;
                }
                readLine(in); // CRLF final
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line);
                if (n < 0) {
                    return null;
                }
                if (n > 1024) {
                    throw new IOException("resposta grande demais");
                }
                List<Object> list = new ArrayList<Object>(n);
                for (int i = 0; i < n; i++) {
                    list.add(readReply(in));
                }
                return list;
            }
            default:
                throw new IOException("resposta Redis desconhecida: " + (char) type);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // já fechado
            }
        }
    }
}
