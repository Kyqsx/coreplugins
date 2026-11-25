package br.com.coreplugins.spawners.economy;

import br.com.coreplugins.spawners.util.Amounts;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * PlayerPoints via reflexão (sem dependência de compilação). Pontos são inteiros: os valores são
 * arredondados para cima na cobrança e para baixo no pagamento.
 */
public final class PlayerPointsProvider implements EconomyProvider {

    private Object api() {
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pl == null || !pl.isEnabled()) {
                return null;
            }
            return pl.getClass().getMethod("getAPI").invoke(pl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method find(Object api, String name, int params) {
        for (Method m : api.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                return m;
            }
        }
        return null;
    }

    @Override
    public String id() {
        return "playerpoints";
    }

    @Override
    public String source() {
        return "PlayerPoints";
    }

    @Override
    public String currencyName() {
        return "pontos";
    }

    @Override
    public boolean isAvailable() {
        return api() != null;
    }

    @Override
    public boolean threadSafe() {
        return false;
    }

    @Override
    public double balance(OfflinePlayer player) {
        try {
            Object api = api();
            Method look = api == null ? null : find(api, "look", 1);
            if (look == null) {
                return 0D;
            }
            Object r = look.invoke(api, player.getUniqueId());
            return r instanceof Number ? ((Number) r).doubleValue() : 0D;
        } catch (Throwable t) {
            return 0D;
        }
    }

    @Override
    public Result withdraw(OfflinePlayer player, double amount) {
        try {
            Object api = api();
            Method take = api == null ? null : find(api, "take", 2);
            if (take == null) {
                return Result.ERROR;
            }
            int points = (int) Math.ceil(amount);
            if (balance(player) < points) {
                return Result.INSUFFICIENT;
            }
            return Boolean.TRUE.equals(take.invoke(api, player.getUniqueId(), points)) ? Result.OK : Result.ERROR;
        } catch (Throwable t) {
            return Result.ERROR;
        }
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        try {
            Object api = api();
            Method give = api == null ? null : find(api, "give", 2);
            if (give == null) {
                return false;
            }
            int points = (int) Math.floor(amount);
            return points <= 0 || Boolean.TRUE.equals(give.invoke(api, player.getUniqueId(), points));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String format(double amount) {
        return Amounts.letter(Math.floor(amount)) + " pontos";
    }
}
