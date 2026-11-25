package br.com.coreplugins.economias.economy;

import br.com.coreplugins.economias.storage.Storage;
import br.com.coreplugins.economias.sync.RedisSync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Saldos em memória (resposta imediata ao jogo) com gravação de VARIAÇÕES no banco.
 * <p>
 * Cada operação altera o saldo local na hora e entra numa fila ordenada; uma thread dedicada grava a
 * variação ("saldo = saldo + x") no banco, em transação e com um id que impede aplicar duas vezes.
 * Assim vários servidores no mesmo MySQL nunca sobrescrevem o saldo um do outro. Depois de gravar, o
 * saldo é relido do banco e substitui o do cache (quando não há mais nada pendente daquele jogador).
 * Mudanças vindas de outros servidores chegam por {@link #onRemoteChange(UUID)} (Redis) ou pelo
 * {@link #resyncOnline()} periódico (rede de segurança).
 * <p>
 * Se o banco estiver fora, a fila fica retendo e tentando de novo (nada se perde); ao desligar, o que
 * sobrar é guardado em {@code pending-ops.log} e reenviado no próximo start.
 */
public final class EconomyService {

    private static final long OPS_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    public enum Result { OK, INSUFFICIENT, ERROR }

    /** Débito confirmado no banco: quem pediu espera o resultado (com prazo). */
    private static final class Debit {
        final String id;
        final UUID from;
        final UUID to;
        final String currency;
        final double amount;
        final double def;
        // tudo abaixo é guardado pelo monitor deste objeto
        boolean started;
        boolean cancelled;
        boolean done;
        boolean abandoned;
        Result result = Result.ERROR;

        Debit(String id, UUID from, UUID to, String currency, double amount, double def) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.currency = currency;
            this.amount = amount;
            this.def = def;
        }
    }

    /** Item da fila: variações (transação única), pedido de releitura ou débito confirmado. */
    private static final class Task {
        final String id;
        final List<Storage.Op> ops;
        final UUID refresh;
        final boolean replayed;
        final Debit debit;

        Task(String id, List<Storage.Op> ops, UUID refresh, boolean replayed) {
            this(id, ops, refresh, replayed, null);
        }

        Task(String id, List<Storage.Op> ops, UUID refresh, boolean replayed, Debit debit) {
            this.id = id;
            this.ops = ops;
            this.refresh = refresh;
            this.replayed = replayed;
            this.debit = debit;
        }
    }

    private final Logger logger;
    private final Storage storage;
    private final CurrencyManager currencies;
    private final File pendingFile;
    private final Object lock = new Object();
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<UUID, Map<String, Double>>();
    private final Map<UUID, String> names = new ConcurrentHashMap<UUID, String>();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Set<UUID> namesToSave = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pending = new HashMap<UUID, Integer>(); // guardado por lock
    private final LinkedBlockingDeque<Task> queue = new LinkedBlockingDeque<Task>();
    private final AtomicInteger replayRemaining = new AtomicInteger();
    private final Thread writer;

    private final List<Debit> unknownDebits = new ArrayList<Debit>(); // só a thread de gravação mexe

    private volatile RedisSync sync;
    private volatile boolean running = true;
    private volatile boolean strict;
    private volatile long debitTimeoutMs = 3000L;
    private volatile boolean dbHealthy = true;
    private volatile Task inFlight;
    private volatile long lastPrune;
    private long lastHousekeeping;

    public EconomyService(Logger logger, Storage storage, CurrencyManager currencies, File dataFolder) {
        this.logger = logger;
        this.storage = storage;
        this.currencies = currencies;
        this.pendingFile = new File(dataFolder, "pending-ops.log");
        replayPendingFile();
        this.writer = new Thread(new Runnable() {
            @Override
            public void run() {
                writerLoop();
            }
        }, "coreEconomias-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public void setSync(RedisSync sync) {
        this.sync = sync;
    }

    /**
     * Modo estrito: remover/pagar só liberam depois que o banco confirmou o débito (UPDATE condicional),
     * o que impede gasto duplo entre servidores. Banco fora do ar = débito recusado (nunca liberado de graça).
     */
    public void setStrict(boolean strict, long timeoutMs) {
        this.strict = strict;
        this.debitTimeoutMs = Math.max(500L, timeoutMs);
    }

    public boolean isStrict() {
        return strict;
    }

    // ---------- ciclo de vida do jogador ----------

    /** Chamado no pré-login (thread assíncrona). Lança exceção se o banco falhar. */
    public void load(UUID id, String name) throws SQLException {
        names.put(id, name);
        online.add(id);
        Map<String, Double> db = storage.loadBalances(id);
        synchronized (lock) {
            // Com variações pendentes, o cache local é mais novo que o banco: mantém.
            if (!cache.containsKey(id) || pendingCount(id) == 0) {
                cache.put(id, fill(db));
            }
        }
        if (!storage.saveName(id, name)) {
            namesToSave.add(id);
        }
    }

    /** Ao sair. Chamar fora da thread principal. */
    public void unload(UUID id) {
        online.remove(id);
        synchronized (lock) {
            if (pendingCount(id) == 0) {
                cache.remove(id);
                names.remove(id);
            }
            // senão a thread de gravação libera o cache quando acabar as pendências
        }
    }

    // ---------- consultas e alterações ----------

    public double get(UUID id, Currency c) {
        Map<String, Double> m = balances(id);
        if (m == null) {
            return 0D;
        }
        Double v = m.get(c.getId());
        if (v == null) {
            v = c.getDefaultBalance();
            m.put(c.getId(), v); // só local: o banco cria a linha na primeira variação
        }
        return v;
    }

    public boolean has(UUID id, Currency c, double amount) {
        return get(id, c) >= amount;
    }

    public boolean set(UUID id, Currency c, double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0D || !running) {
            return false;
        }
        synchronized (lock) {
            Map<String, Double> m = balances(id);
            if (m == null) {
                return false;
            }
            m.put(c.getId(), amount);
            enqueue(Collections.singletonList(
                    new Storage.Op(id, c.getId(), Storage.Op.SET, amount, c.getDefaultBalance())));
            return true;
        }
    }

    public boolean add(UUID id, Currency c, double amount) {
        if (!(amount > 0D) || Double.isInfinite(amount) || !running) {
            return false;
        }
        synchronized (lock) {
            Map<String, Double> m = balances(id);
            if (m == null) {
                return false;
            }
            double next = get(id, c) + amount;
            if (Double.isInfinite(next)) {
                return false;
            }
            m.put(c.getId(), next);
            enqueue(Collections.singletonList(
                    new Storage.Op(id, c.getId(), Storage.Op.ADD, amount, c.getDefaultBalance())));
            return true;
        }
    }

    public boolean remove(UUID id, Currency c, double amount) {
        return tryRemove(id, c, amount) == Result.OK;
    }

    public boolean transfer(UUID from, UUID to, Currency c, double amount) {
        return tryTransfer(from, to, c, amount) == Result.OK;
    }

    /** INSUFFICIENT = saldo insuficiente; ERROR = valor inválido, banco indisponível ou prazo estourado. */
    public Result tryRemove(UUID id, Currency c, double amount) {
        if (!(amount > 0D) || Double.isInfinite(amount) || !running) {
            return Result.ERROR;
        }
        if (strict) {
            return debit(id, null, c, amount);
        }
        synchronized (lock) {
            Map<String, Double> m = balances(id);
            if (m == null) {
                return Result.ERROR;
            }
            double cur = get(id, c);
            if (cur < amount) {
                return Result.INSUFFICIENT;
            }
            m.put(c.getId(), cur - amount);
            enqueue(Collections.singletonList(
                    new Storage.Op(id, c.getId(), Storage.Op.ADD, -amount, c.getDefaultBalance())));
            return Result.OK;
        }
    }

    public Result tryTransfer(UUID from, UUID to, Currency c, double amount) {
        if (!(amount > 0D) || Double.isInfinite(amount) || from.equals(to) || !running) {
            return Result.ERROR;
        }
        if (strict) {
            return debit(from, to, c, amount);
        }
        synchronized (lock) {
            Map<String, Double> a = balances(from);
            Map<String, Double> b = balances(to);
            if (a == null || b == null) {
                return Result.ERROR;
            }
            double curA = get(from, c);
            double nextB = get(to, c) + amount;
            if (Double.isInfinite(nextB)) {
                return Result.ERROR;
            }
            if (curA < amount) {
                return Result.INSUFFICIENT;
            }
            a.put(c.getId(), curA - amount);
            b.put(c.getId(), nextB);
            // Os dois lados vão na MESMA transação: ou os dois gravam ou nenhum.
            List<Storage.Op> ops = new ArrayList<Storage.Op>(2);
            ops.add(new Storage.Op(from, c.getId(), Storage.Op.ADD, -amount, c.getDefaultBalance()));
            ops.add(new Storage.Op(to, c.getId(), Storage.Op.ADD, amount, c.getDefaultBalance()));
            enqueue(ops);
            return Result.OK;
        }
    }

    /** Débito confirmado: entra na fila (depois das variações já pendentes) e espera o banco responder. */
    private Result debit(UUID from, UUID to, Currency c, double amount) {
        if (!dbHealthy) {
            return Result.ERROR; // banco fora: falha fechada, sem travar a thread principal
        }
        final Debit d;
        synchronized (lock) {
            Map<String, Double> a = balances(from);
            if (a == null) {
                return Result.ERROR;
            }
            if (get(from, c) < amount) {
                return Result.INSUFFICIENT; // nem o saldo local cobre: recusa sem ir ao banco
            }
            if (to != null) {
                if (balances(to) == null || Double.isInfinite(get(to, c) + amount)) {
                    return Result.ERROR;
                }
            }
            d = new Debit(UUID.randomUUID().toString(), from, to, c.getId(), amount, c.getDefaultBalance());
            bumpPending(from);
            if (to != null) {
                bumpPending(to);
            }
            queue.add(new Task(d.id, null, null, false, d));
        }
        long end = System.nanoTime() + debitTimeoutMs * 1000000L;
        synchronized (d) {
            try {
                while (!d.done) {
                    long left = (end - System.nanoTime()) / 1000000L;
                    if (left <= 0) {
                        break;
                    }
                    d.wait(left);
                }
                if (!d.done && !d.started) {
                    d.cancelled = true; // ainda na fila: a thread de gravação vai pular
                    return Result.ERROR;
                }
                // Já executando no banco: não dá para cancelar, espera mais um pouco pelo resultado real.
                long end2 = System.nanoTime() + 8000L * 1000000L;
                while (!d.done) {
                    long left = (end2 - System.nanoTime()) / 1000000L;
                    if (left <= 0) {
                        break;
                    }
                    d.wait(left);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (d.done) {
                return d.result;
            }
            // Sem resposta do banco: recusa, e se ele acabar debitando, a thread de gravação devolve.
            d.abandoned = true;
            logger.severe("Débito " + d.id + " sem resposta do banco; recusado. Se constar no banco, será estornado.");
            return Result.ERROR;
        }
    }

    // ---------- jogadores offline ----------

    /** Procura no banco (bloqueante: chamar de thread assíncrona quando possível). */
    public Storage.PlayerRef findPlayer(String name) {
        Storage.PlayerRef ref = storage.findByName(name);
        if (ref != null) {
            names.putIfAbsent(ref.uuid, ref.name);
        }
        return ref;
    }

    /** Garante o saldo do jogador em memória (lê do banco se preciso). */
    public boolean preload(UUID id) {
        return balances(id) != null;
    }

    public String nameOf(UUID id) {
        return names.get(id);
    }

    // ---------- sincronização entre servidores ----------

    /** Outro servidor mexeu no saldo deste jogador: relê do banco (se estiver em cache aqui). */
    public void onRemoteChange(UUID id) {
        if (running && cache.containsKey(id)) {
            queue.add(new Task(null, null, id, false));
        }
    }

    /** Relê o saldo de todos os jogadores online. Rede de segurança contra mensagens perdidas. */
    public void resyncOnline() {
        if (!running) {
            return;
        }
        for (UUID id : online) {
            if (cache.containsKey(id)) {
                queue.add(new Task(null, null, id, false));
            }
        }
    }

    /** Manutenção periódica (thread assíncrona): reenvia nomes pendentes e libera cache de quem saiu. */
    public void maintenance() {
        for (UUID id : new ArrayList<UUID>(namesToSave)) {
            String n = names.get(id);
            if (n == null || storage.saveName(id, n)) {
                namesToSave.remove(id);
            }
        }
        synchronized (lock) {
            for (UUID id : new ArrayList<UUID>(cache.keySet())) {
                if (!online.contains(id) && pendingCount(id) == 0) {
                    cache.remove(id);
                    names.remove(id);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastPrune > 60L * 60 * 1000) {
            lastPrune = now;
            storage.pruneOps(OPS_RETENTION_MS);
        }
    }

    // ---------- desligamento ----------

    /**
     * Espera a fila esvaziar (até o prazo). O que não der para gravar vai para pending-ops.log e é
     * reenviado no próximo start; o registro de operações do banco impede duplicar.
     */
    public void shutdown() {
        running = false;
        writer.interrupt();
        try {
            writer.join(15000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Task> left = new ArrayList<Task>();
        Task flying = inFlight;
        if (flying != null && flying.ops != null) {
            left.add(flying);
        }
        queue.drainTo(left);
        List<Task> toSave = new ArrayList<Task>();
        for (Task t : left) {
            if (t.ops != null) {
                toSave.add(t);
            } else if (t.debit != null) {
                synchronized (t.debit) { // não executado: libera quem espera, sem cobrar
                    if (!t.debit.done) {
                        t.debit.cancelled = true;
                        t.debit.done = true;
                        t.debit.result = Result.ERROR;
                        t.debit.notifyAll();
                    }
                }
            }
        }
        if (!toSave.isEmpty()) {
            persistPending(toSave);
        }
    }

    // ---------- fila e thread de gravação ----------

    private void enqueue(List<Storage.Op> ops) {
        Set<UUID> who = new LinkedHashSet<UUID>();
        for (Storage.Op op : ops) {
            who.add(op.uuid);
        }
        for (UUID id : who) {
            bumpPending(id);
        }
        queue.add(new Task(UUID.randomUUID().toString(), ops, null, false));
    }

    private void bumpPending(UUID id) { // chamar com lock
        Integer n = pending.get(id);
        pending.put(id, n == null ? 1 : n + 1);
    }

    private void dropPending(UUID id) {
        synchronized (lock) {
            int n = pendingCount(id) - 1;
            if (n <= 0) {
                pending.remove(id);
            } else {
                pending.put(id, n);
            }
        }
    }

    /** Efeito local (cache) de uma operação já confirmada no banco. */
    private void applyLocal(UUID id, String currency, double delta, double def) {
        synchronized (lock) {
            Map<String, Double> m = cache.get(id);
            if (m != null) {
                Double cur = m.get(currency);
                m.put(currency, (cur == null ? def : cur) + delta);
            }
        }
    }

    private int pendingCount(UUID id) {
        Integer n = pending.get(id);
        return n == null ? 0 : n;
    }

    private void writerLoop() {
        long backoff = 500L;
        while (true) {
            Task t;
            try {
                t = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                t = queue.poll(); // desligando: ainda tenta gravar o que já está na fila
            }
            long now = System.currentTimeMillis();
            if (running && now - lastHousekeeping > 2000L) {
                lastHousekeeping = now;
                housekeeping();
            }
            if (t == null) {
                if (!running) {
                    return;
                }
                continue;
            }
            inFlight = t;
            if (t.refresh != null) {
                reconcile(t.refresh, false);
                inFlight = null;
                continue;
            }
            if (t.debit != null) {
                runDebit(t.debit);
                inFlight = null;
                continue;
            }
            try {
                storage.apply(t.id, t.ops);
                dbHealthy = true;
            } catch (SQLException | RuntimeException e) {
                dbHealthy = false;
                logger.log(Level.SEVERE, "Falha ao gravar no banco; as alterações ficam na fila e serão repetidas.", e);
                queue.addFirst(t);
                inFlight = null;
                if (!running) {
                    return; // shutdown() guarda em pending-ops.log
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    // desligando: o laço trata na próxima volta
                }
                backoff = Math.min(backoff * 2, 10000L);
                continue;
            }
            backoff = 500L;
            afterApplied(t);
            inFlight = null;
        }
    }

    private void runDebit(Debit d) {
        boolean skip;
        synchronized (d) {
            if (d.cancelled) {
                d.done = true;
                d.result = Result.ERROR;
                d.notifyAll();
                skip = true;
            } else {
                d.started = true;
                skip = false;
            }
        }
        if (skip) {
            dropPending(d.from);
            if (d.to != null) {
                dropPending(d.to);
            }
            return;
        }
        Result outcome;
        boolean unknown = false;
        try {
            Storage.DebitResult r = storage.debit(d.id, d.from, d.currency, d.amount, d.def, d.to);
            outcome = r == Storage.DebitResult.OK ? Result.OK : Result.INSUFFICIENT;
            dbHealthy = true;
        } catch (SQLException | RuntimeException e) {
            dbHealthy = false;
            logger.log(Level.SEVERE, "Falha no débito " + d.id + " (" + d.amount + " de " + d.from + ").", e);
            try {
                outcome = storage.wasApplied(d.id) ? Result.OK : Result.ERROR; // dúvida no commit: confere o registro
                dbHealthy = true; // a conferência respondeu: o banco está de pé
            } catch (SQLException | RuntimeException e2) {
                outcome = Result.ERROR;
                unknown = true;
            }
        }
        boolean refund;
        synchronized (d) {
            d.done = true;
            d.result = outcome;
            refund = d.abandoned && outcome == Result.OK;
            d.notifyAll();
        }
        if (outcome == Result.OK) {
            applyLocal(d.from, d.currency, -d.amount, d.def);
            if (d.to != null) {
                applyLocal(d.to, d.currency, d.amount, d.def);
            }
        }
        dropPending(d.from);
        if (d.to != null) {
            dropPending(d.to);
        }
        if (unknown) {
            unknownDebits.add(d);
            logger.severe("Não foi possível confirmar se o débito " + d.id + " foi aplicado; será conferido quando o banco voltar.");
        }
        if (refund) {
            refund(d);
        }
        afterDebit(d);
    }

    private void afterDebit(Debit d) {
        Set<UUID> who = new LinkedHashSet<UUID>();
        who.add(d.from);
        if (d.to != null) {
            who.add(d.to);
        }
        for (UUID id : who) {
            reconcile(id, true);
            RedisSync s = sync;
            if (s != null) {
                s.publish(id);
            }
        }
    }

    /** O jogador foi recusado mas o banco acabou debitando: devolve (estorno). */
    private void refund(Debit d) {
        logger.severe("Estornando débito " + d.id + " (" + d.amount + " de " + d.from + "): foi aplicado depois de recusado.");
        List<Storage.Op> ops = new ArrayList<Storage.Op>(2);
        ops.add(new Storage.Op(d.from, d.currency, Storage.Op.ADD, d.amount, d.def));
        if (d.to != null) {
            ops.add(new Storage.Op(d.to, d.currency, Storage.Op.ADD, -d.amount, d.def));
        }
        synchronized (lock) {
            applyLocal(d.from, d.currency, d.amount, d.def);
            if (d.to != null) {
                applyLocal(d.to, d.currency, -d.amount, d.def);
            }
            enqueue(ops);
        }
    }

    /** A cada ~2 s na thread de gravação: reconfere débitos duvidosos e testa se o banco voltou. */
    private void housekeeping() {
        if (!dbHealthy) {
            try {
                storage.loadBalances(new UUID(0L, 0L));
                dbHealthy = true;
                logger.info("Banco de dados voltou a responder.");
            } catch (SQLException | RuntimeException e) {
                return;
            }
        }
        for (Iterator<Debit> it = unknownDebits.iterator(); it.hasNext(); ) {
            Debit d = it.next();
            try {
                if (storage.wasApplied(d.id)) {
                    refund(d);
                }
                it.remove();
            } catch (SQLException | RuntimeException e) {
                return; // banco ainda fora
            }
        }
    }

    private void afterApplied(Task t) {
        Set<UUID> who = new LinkedHashSet<UUID>();
        for (Storage.Op op : t.ops) {
            who.add(op.uuid);
        }
        for (UUID id : who) {
            dropPending(id);
        }
        for (UUID id : who) {
            reconcile(id, true);
            RedisSync s = sync;
            if (s != null) {
                s.publish(id);
            }
        }
        if (t.replayed && replayRemaining.decrementAndGet() == 0) {
            if (!pendingFile.delete()) {
                logger.warning("Não foi possível apagar " + pendingFile.getName() + " (será ignorado por já constar no banco).");
            } else {
                logger.info("Operações pendentes reenviadas ao banco.");
            }
        }
    }

    /** Relê o saldo do banco e, se não houver nada pendente desse jogador, adota-o no cache. */
    private void reconcile(UUID id, boolean afterWrite) {
        Map<String, Double> db;
        try {
            db = storage.loadBalances(id);
            dbHealthy = true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Não foi possível reler o saldo de " + id + " (tenta no próximo ciclo).", e);
            return;
        }
        for (Map.Entry<String, Double> e : afterWrite ? db.entrySet() : Collections.<Map.Entry<String, Double>>emptySet()) {
            if (e.getValue() < 0D) {
                logger.warning("Saldo negativo de " + id + " em '" + e.getKey() + "' (" + e.getValue()
                        + "): gasto simultâneo em dois servidores. Confira o jogador.");
            }
        }
        synchronized (lock) {
            if (pendingCount(id) > 0) {
                return; // há variações novas ainda não gravadas: o cache local está mais novo
            }
            if (!online.contains(id)) {
                if (afterWrite) {
                    cache.remove(id);
                    names.remove(id);
                }
                return;
            }
            if (cache.containsKey(id)) {
                cache.put(id, fill(db));
            }
        }
    }

    // ---------- arquivo de pendências ----------

    private void persistPending(List<Task> tasks) {
        try (Writer w = new OutputStreamWriter(
                new java.io.FileOutputStream(pendingFile, true), StandardCharsets.UTF_8)) {
            for (Task t : tasks) {
                StringBuilder sb = new StringBuilder(t.id).append('|');
                boolean first = true;
                for (Storage.Op op : t.ops) {
                    if (!first) {
                        sb.append(';');
                    }
                    first = false;
                    sb.append(op.uuid).append(',')
                            .append(URLEncoder.encode(op.currency, "UTF-8")).append(',')
                            .append(op.type).append(',')
                            .append(Double.toString(op.value)).append(',')
                            .append(Double.toString(op.def));
                }
                w.write(sb.append('\n').toString());
            }
            logger.severe(tasks.size() + " grupo(s) de alterações não puderam ser gravados no banco e foram guardados em "
                    + pendingFile.getName() + "; serão reenviados no próximo início.");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "FALHA ao guardar as alterações pendentes em " + pendingFile.getAbsolutePath(), e);
        }
    }

    private void replayPendingFile() {
        if (!pendingFile.isFile()) {
            return;
        }
        List<Task> tasks = new ArrayList<Task>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(pendingFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    int bar = line.indexOf('|');
                    String id = line.substring(0, bar);
                    List<Storage.Op> ops = new ArrayList<Storage.Op>();
                    for (String part : line.substring(bar + 1).split(";")) {
                        String[] f = part.split(",");
                        ops.add(new Storage.Op(UUID.fromString(f[0]), URLDecoder.decode(f[1], "UTF-8"),
                                Integer.parseInt(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4])));
                    }
                    tasks.add(new Task(id, ops, null, true));
                } catch (RuntimeException ex) {
                    logger.severe("Linha inválida em " + pendingFile.getName() + " ignorada: " + line);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Não foi possível ler " + pendingFile.getAbsolutePath(), e);
            return;
        }
        if (tasks.isEmpty()) {
            return;
        }
        replayRemaining.set(tasks.size());
        synchronized (lock) {
            for (Task t : tasks) {
                Set<UUID> per = new HashSet<UUID>();
                for (Storage.Op op : t.ops) {
                    per.add(op.uuid);
                }
                for (UUID id : per) {
                    Integer n = pending.get(id);
                    pending.put(id, n == null ? 1 : n + 1);
                }
            }
        }
        queue.addAll(tasks);
        logger.warning(tasks.size() + " grupo(s) de alterações pendentes de uma sessão anterior serão reenviados ao banco.");
    }

    // ---------- util ----------

    private Map<String, Double> fill(Map<String, Double> db) {
        Map<String, Double> map = new ConcurrentHashMap<String, Double>(db);
        for (Currency c : currencies.all()) {
            if (!map.containsKey(c.getId())) {
                map.put(c.getId(), c.getDefaultBalance());
            }
        }
        return map;
    }

    private Map<String, Double> balances(UUID id) {
        Map<String, Double> map = cache.get(id);
        if (map != null) {
            return map;
        }
        synchronized (lock) {
            map = cache.get(id);
            if (map != null) {
                return map;
            }
            try {
                map = fill(storage.loadBalances(id));
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Não foi possível carregar os saldos de " + id, e);
                return null;
            }
            cache.put(id, map);
            return map;
        }
    }
}
