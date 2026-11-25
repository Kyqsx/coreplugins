package br.com.coreplugins.spawners.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cores (&) e substituição de {placeholders}. */
public final class Text {

    private Text() {
    }

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public static String apply(String s, Map<String, String> vars) {
        if (s == null) {
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return s;
        }
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }

    /** Aplica os placeholders e depois as cores. */
    public static String render(String s, Map<String, String> vars) {
        return color(apply(s, vars));
    }

    public static List<String> render(List<String> lines, Map<String, String> vars) {
        List<String> out = new ArrayList<String>();
        if (lines != null) {
            for (String l : lines) {
                out.add(render(l, vars));
            }
        }
        return out;
    }

    /** vars("a","1","b","2") -> {a=1, b=2} */
    public static Map<String, String> vars(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 3725 -> "1h 2m 5s" (sempre mostra pelo menos os segundos). */
    public static String duration(long seconds) {
        long s = Math.max(0L, seconds);
        long d = s / 86400L;
        long h = (s % 86400L) / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("d ");
        }
        if (h > 0) {
            sb.append(h).append("h ");
        }
        if (m > 0) {
            sb.append(m).append("m ");
        }
        if (sec > 0 || sb.length() == 0) {
            sb.append(sec).append("s");
        }
        return sb.toString().trim();
    }
}
