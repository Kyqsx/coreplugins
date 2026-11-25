package br.com.coreplugins.spawners.manager;

import br.com.coreplugins.spawners.SpawnersProduct;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Locale;

/**
 * Limite de geradores por jogador.
 * <ul>
 *   <li>{@code corespawners.limit.<N>}: limite N (vale o maior que o jogador tiver; dê a um grupo no LuckPerms).</li>
 *   <li>{@code corespawners.limit.0}, {@code corespawners.limit.unlimited}, {@code corespawners.limit.*} e
 *       {@code corespawners.limit.bypass}: sem limite.</li>
 *   <li>Sem nenhuma dessas permissões vale {@code limits.default} do config.yml (0 = ilimitado).</li>
 * </ul>
 */
public final class LimitManager {

    public static final String PREFIX = "corespawners.limit.";

    private final SpawnersProduct plugin;

    public LimitManager(SpawnersProduct plugin) {
        this.plugin = plugin;
    }

    /** Limite do jogador; 0 = ilimitado. */
    public long limitOf(Player p) {
        if (p.hasPermission(PREFIX + "bypass")) {
            return 0L;
        }
        boolean found = false;
        long best = 0L;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String perm = info.getPermission().toLowerCase(Locale.ROOT);
            if (!perm.startsWith(PREFIX)) {
                continue;
            }
            String tail = perm.substring(PREFIX.length());
            if (tail.equals("unlimited") || tail.equals("*")) {
                return 0L;
            }
            try {
                long n = Long.parseLong(tail);
                if (n <= 0L) {
                    return 0L;
                }
                found = true;
                best = Math.max(best, n);
            } catch (NumberFormatException ignored) {
                // corespawners.limit.bypass e afins: não são números
            }
        }
        return found ? best : Math.max(0L, plugin.getConfig().getLong("limits.default", 0L));
    }

    /** STACK (padrão): conta a soma dos stacks. BLOCKS: conta os blocos de gerador. */
    public boolean countStacks() {
        return !"BLOCKS".equalsIgnoreCase(plugin.getConfig().getString("limits.count", "STACK"));
    }

    /** Quanto o jogador já usa do limite, no modo configurado. */
    public long used(java.util.UUID owner) {
        long[] t = plugin.spawners().totalsOf(owner);
        return countStacks() ? t[0] : t[1];
    }
}
