package br.com.coreplugins.economias.hook;

import br.com.coreplugins.economias.EconomiasProduct;
import br.com.coreplugins.economias.economy.Currency;
import br.com.coreplugins.economias.economy.EconomyService;
import br.com.coreplugins.economias.storage.Storage;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Expõe UMA das economias ao Vault (a de vault-currency no config.yml).
 * Esta classe só é carregada quando o Vault está presente.
 */
public final class VaultHook implements Economy {

    private final EconomiasProduct plugin;
    private final EconomyService service;
    private final String currencyId;
    private volatile Currency lastKnown;

    private VaultHook(EconomiasProduct plugin, EconomyService service, Currency currency) {
        this.plugin = plugin;
        this.service = service;
        this.currencyId = currency.getId();
        this.lastKnown = currency;
    }

    /** A economia atual (o menu pode recarregar os arquivos; o id permanece). */
    private Currency cur() {
        Currency c = plugin.getCurrencies().get(currencyId);
        if (c != null) {
            lastKnown = c;
            return c;
        }
        return lastKnown;
    }

    /** Registra no ServicesManager (dono = loader) e devolve o provider, ou null se a economia não existe. */
    public static Object register(EconomiasProduct plugin, String currencyId) {
        Currency c = plugin.getCurrencies().get(currencyId);
        if (c == null) {
            plugin.getLogger().warning("vault-currency '" + currencyId
                    + "' não existe em economies/: integração com o Vault desativada.");
            return null;
        }
        VaultHook hook = new VaultHook(plugin, plugin.getService(), c);
        plugin.getServer().getServicesManager().register(Economy.class, hook, plugin.getLoader(), ServicePriority.Highest);
        plugin.getLogger().info("Vault: economia '" + c.getId() + "' registrada.");
        return hook;
    }

    // ---------- resolução de contas ----------

    private UUID uuidOf(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            return p.getUniqueId();
        }
        Storage.PlayerRef ref = service.findPlayer(name);
        return ref == null ? null : ref.uuid;
    }

    private double balanceOf(UUID id) {
        return id == null ? 0D : service.get(id, cur());
    }

    private EconomyResponse deposit(UUID id, double amount) {
        if (id == null) {
            return new EconomyResponse(0D, 0D, ResponseType.FAILURE, "Conta não encontrada");
        }
        if (Double.isNaN(amount) || amount < 0D) {
            return new EconomyResponse(0D, balanceOf(id), ResponseType.FAILURE, "Valor inválido");
        }
        if (amount > 0D && !service.add(id, cur(), amount)) {
            return new EconomyResponse(0D, balanceOf(id), ResponseType.FAILURE, "Não foi possível depositar");
        }
        return new EconomyResponse(amount, balanceOf(id), ResponseType.SUCCESS, null);
    }

    private EconomyResponse withdraw(UUID id, double amount) {
        if (id == null) {
            return new EconomyResponse(0D, 0D, ResponseType.FAILURE, "Conta não encontrada");
        }
        if (Double.isNaN(amount) || amount < 0D) {
            return new EconomyResponse(0D, balanceOf(id), ResponseType.FAILURE, "Valor inválido");
        }
        if (amount > 0D) {
            EconomyService.Result r = service.tryRemove(id, cur(), amount);
            if (r == EconomyService.Result.INSUFFICIENT) {
                return new EconomyResponse(0D, balanceOf(id), ResponseType.FAILURE, "Saldo insuficiente");
            }
            if (r != EconomyService.Result.OK) {
                return new EconomyResponse(0D, balanceOf(id), ResponseType.FAILURE,
                        "Não foi possível confirmar o débito no banco de dados");
            }
        }
        return new EconomyResponse(amount, balanceOf(id), ResponseType.SUCCESS, null);
    }

    private EconomyResponse noBanks() {
        return new EconomyResponse(0D, 0D, ResponseType.NOT_IMPLEMENTED, "Bancos não são suportados");
    }

    // ---------- informações ----------

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "coreEconomias"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return cur().format(amount) + " " + cur().unit(amount); }
    @Override public String currencyNamePlural() { return cur().getPlural(); }
    @Override public String currencyNameSingular() { return cur().getSingular(); }

    // ---------- contas ----------

    @Override public boolean hasAccount(String name) { return uuidOf(name) != null; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return true; }

    @Override public boolean createPlayerAccount(String name) { return uuidOf(name) != null; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String world) { return true; }

    // ---------- saldo ----------

    @Override public double getBalance(String name) { return balanceOf(uuidOf(name)); }
    @Override public double getBalance(OfflinePlayer player) { return balanceOf(player.getUniqueId()); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String name, double amount) { return getBalance(name) >= amount; }
    @Override public boolean has(OfflinePlayer player, double amount) { return getBalance(player) >= amount; }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }

    // ---------- movimentação ----------

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdraw(uuidOf(name), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return withdraw(player.getUniqueId(), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(uuidOf(name), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return deposit(player.getUniqueId(), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) { return depositPlayer(player, amount); }

    // ---------- bancos (não suportados) ----------

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
