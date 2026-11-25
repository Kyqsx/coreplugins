package br.com.coreplugins.spawners.placeholder;

import br.com.coreplugins.spawners.SpawnersProduct;
import br.com.coreplugins.spawners.manager.StatsManager;
import br.com.coreplugins.spawners.model.PlayerStats;
import br.com.coreplugins.spawners.util.Amounts;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Placeholders do coreSpawners para o PlaceholderAPI (scoreboard, holograma, tab...).
 *
 * <p>Qualquer valor numérico aceita o sufixo {@code _short} (1.5K) e {@code _raw} (1500, sem formatação);
 * sem sufixo sai no padrão brasileiro (1.500).
 *
 * <pre>
 * Jogador:  %corespawners_stack%  _spawners  _drops  _kills  _sold  _sold_items  _sold_&lt;moeda&gt;
 *           _limit  _limit_used  _limit_left  _position_stack|kills|sales
 * Ranking:  %corespawners_top_&lt;stack|kills|sales&gt;_&lt;posição&gt;_name|value%
 * </pre>
 */
public final class SpawnersExpansion extends PlaceholderExpansion {

    private final SpawnersProduct plugin;

    public SpawnersExpansion(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "corespawners";
    }

    @Override
    public String getAuthor() {
        return "CorePlugins";
    }

    @Override
    public String getVersion() {
        return plugin.buildInfo().version;
    }

    /** Continua registrado quando o PlaceholderAPI recarrega (/papi reload). */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        StatsManager st = plugin.stats();
        if (st == null || params == null) {
            return "";
        }
        String p = params.toLowerCase(Locale.ROOT);
        String mode = "";
        if (p.endsWith("_short")) {
            mode = "short";
            p = p.substring(0, p.length() - 6);
        } else if (p.endsWith("_raw")) {
            mode = "raw";
            p = p.substring(0, p.length() - 4);
        }
        if (p.startsWith("top_")) {
            return top(st, p.substring(4), mode);
        }
        if (player == null) {
            return "";
        }
        UUID id = player.getUniqueId();
        StatsManager.Owned owned = st.owned(id);
        PlayerStats ps = st.of(id);
        if (p.equals("stack")) {
            return num(owned.stack, mode);
        }
        if (p.equals("spawners")) {
            return num(owned.blocks, mode);
        }
        if (p.equals("drops")) {
            return num(owned.drops, mode);
        }
        if (p.equals("kills")) {
            return num(ps == null ? 0L : ps.kills(), mode);
        }
        if (p.equals("sold_items")) {
            return num(ps == null ? 0L : ps.soldItems(), mode);
        }
        if (p.equals("sold")) {
            return num(ps == null || st.snapshot().salesCurrency.isEmpty() ? 0D : ps.sales(st.snapshot().salesCurrency), mode);
        }
        if (p.startsWith("sold_")) {
            return num(ps == null ? 0D : ps.sales(p.substring(5)), mode);
        }
        if (p.startsWith("position_")) {
            String cat = p.substring(9);
            if (!StatsManager.isCategory(cat)) {
                return null;
            }
            int pos = st.position(cat, id);
            return pos <= 0 ? emptyName() : String.valueOf(pos);
        }
        if (p.equals("limit") || p.equals("limit_used") || p.equals("limit_left")) {
            return limit(st, id, p, mode);
        }
        return null; // placeholder desconhecido: o PlaceholderAPI deixa o texto como está
    }

    private String limit(StatsManager st, UUID id, String key, String mode) {
        long limit = st.limitOf(id);
        if (limit < 0L) {
            return ""; // jogador offline: o limite só é conhecido para quem está online
        }
        long used = plugin.limits().countStacks() ? st.owned(id).stack : st.owned(id).blocks;
        if (key.equals("limit_used")) {
            return num(used, mode);
        }
        if (limit == 0L) {
            return mode.equals("raw") ? "0" : unlimited();
        }
        return num(key.equals("limit") ? limit : Math.max(0L, limit - used), mode);
    }

    private String top(StatsManager st, String rest, String mode) {
        String[] parts = rest.split("_");
        if (parts.length != 3 || !StatsManager.isCategory(parts[0])) {
            return null;
        }
        int pos;
        try {
            pos = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        boolean name = parts[2].equals("name");
        if (!name && !parts[2].equals("value")) {
            return null;
        }
        List<StatsManager.Entry> list = st.top(parts[0]);
        if (pos < 1 || pos > list.size()) {
            return name ? emptyName() : emptyValue();
        }
        StatsManager.Entry e = list.get(pos - 1);
        return name ? e.name : num(e.value, mode);
    }

    private String emptyName() {
        return plugin.getConfig().getString("ranking.placeholders.empty-name", "-");
    }

    private String emptyValue() {
        return plugin.getConfig().getString("ranking.placeholders.empty-value", "0");
    }

    private String unlimited() {
        return plugin.getConfig().getString("ranking.placeholders.unlimited", "\u221e");
    }

    private static String num(double v, String mode) {
        if (mode.equals("short")) {
            return Amounts.letter(v);
        }
        if (mode.equals("raw")) {
            return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return Amounts.number(v);
    }
}
