package br.com.coreplugins.spawners.economy;

import org.bukkit.OfflinePlayer;

/** Uma moeda do coreEconomias, acessada pelo id (a cada chamada, para acompanhar recargas do coreEconomias). */
public final class CoreCurrencyProvider implements EconomyProvider {

    private final CoreEconomiasLink link;
    private final String id;

    public CoreCurrencyProvider(CoreEconomiasLink link, String id) {
        this.link = link;
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String source() {
        return CoreEconomiasLink.PRODUCT_NAME;
    }

    @Override
    public String currencyName() {
        Object c = link.currency(id);
        if (c == null) {
            return id;
        }
        String plural = link.plural(c);
        return plural.isEmpty() ? id : plural;
    }

    @Override
    public boolean isAvailable() {
        return link.ready() && link.currency(id) != null;
    }

    /** O serviço de economia do coreEconomias usa travas próprias e filas: pode ser chamado de qualquer thread. */
    @Override
    public boolean threadSafe() {
        return true;
    }

    @Override
    public double balance(OfflinePlayer player) {
        Object c = link.currency(id);
        return c == null ? 0D : link.get(player.getUniqueId(), c);
    }

    @Override
    public Result withdraw(OfflinePlayer player, double amount) {
        Object c = link.currency(id);
        if (c == null) {
            return Result.ERROR;
        }
        String r = link.tryRemove(player.getUniqueId(), c, amount);
        if ("OK".equals(r)) {
            return Result.OK;
        }
        return "INSUFFICIENT".equals(r) ? Result.INSUFFICIENT : Result.ERROR;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        Object c = link.currency(id);
        return c != null && link.add(player.getUniqueId(), c, amount);
    }

    @Override
    public String format(double amount) {
        Object c = link.currency(id);
        return c == null ? String.valueOf(amount) : link.format(c, amount);
    }
}
