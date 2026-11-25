package br.com.coreplugins.spawners.economy;

import br.com.coreplugins.spawners.util.Amounts;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Economia principal do Vault. Só é instanciada quando o Vault está habilitado (os imports do Vault
 * ficam restritos a esta classe). Os plugins de economia do Vault não garantem thread-safety, então
 * tudo roda na thread principal.
 */
public final class VaultProvider implements EconomyProvider {

    private Economy eco() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    @Override
    public String id() {
        return "vault";
    }

    @Override
    public String source() {
        Economy e = eco();
        return "Vault" + (e == null ? "" : " (" + e.getName() + ")");
    }

    @Override
    public String currencyName() {
        Economy e = eco();
        String n = e == null ? null : e.currencyNamePlural();
        return n == null || n.isEmpty() ? "dinheiro" : n;
    }

    @Override
    public boolean isAvailable() {
        Economy e = eco();
        return e != null && e.isEnabled();
    }

    @Override
    public boolean threadSafe() {
        return false;
    }

    @Override
    public double balance(OfflinePlayer player) {
        Economy e = eco();
        return e == null ? 0D : e.getBalance(player);
    }

    @Override
    public Result withdraw(OfflinePlayer player, double amount) {
        Economy e = eco();
        if (e == null) {
            return Result.ERROR;
        }
        if (!e.has(player, amount)) {
            return Result.INSUFFICIENT;
        }
        EconomyResponse r = e.withdrawPlayer(player, amount);
        return r.transactionSuccess() ? Result.OK : Result.ERROR;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        Economy e = eco();
        return e != null && e.depositPlayer(player, amount).transactionSuccess();
    }

    @Override
    public String format(double amount) {
        Economy e = eco();
        try {
            if (e != null) {
                return e.format(amount);
            }
        } catch (Throwable ignored) {
            // cai para o formato simples
        }
        return Amounts.letter(amount);
    }
}
