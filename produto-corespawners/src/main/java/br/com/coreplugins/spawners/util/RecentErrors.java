package br.com.coreplugins.spawners.util;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/** Guarda os últimos avisos e erros que o plugin escreveu no log, para o /spawners debug. */
public final class RecentErrors extends Handler {

    /** Um registro guardado. */
    public static final class Item {
        public final long time;
        public final Level level;
        public final String text;

        Item(long time, Level level, String text) {
            this.time = time;
            this.level = level;
            this.text = text;
        }
    }

    private final int max;
    private final LinkedList<Item> items = new LinkedList<Item>();

    public RecentErrors(int max) {
        this.max = Math.max(1, max);
        setLevel(Level.WARNING);
    }

    @Override
    public void publish(LogRecord r) {
        if (r == null || r.getLevel().intValue() < Level.WARNING.intValue()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String msg = r.getMessage();
        sb.append(msg == null ? "" : msg);
        Throwable t = r.getThrown();
        if (t != null) {
            sb.append(" [").append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            sb.append(']');
        }
        String text = sb.toString();
        if (text.length() > 220) {
            text = text.substring(0, 217) + "...";
        }
        synchronized (items) {
            items.addFirst(new Item(r.getMillis(), r.getLevel(), text));
            while (items.size() > max) {
                items.removeLast();
            }
        }
    }

    /** Do mais recente para o mais antigo. */
    public List<Item> recent() {
        synchronized (items) {
            return new ArrayList<Item>(items);
        }
    }

    public int count() {
        synchronized (items) {
            return items.size();
        }
    }

    @Override
    public void flush() {
        // nada a descarregar
    }

    @Override
    public void close() {
        synchronized (items) {
            items.clear();
        }
    }
}
