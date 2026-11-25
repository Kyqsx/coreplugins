package br.com.coreplugins.spawners.economy;

import br.com.coreplugins.spawners.util.Amounts;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Integração genérica com QUALQUER plugin de economia: lê o saldo por um placeholder do PlaceholderAPI
 * e dá/tira por comandos do console (definidos em economies.yml, seção custom).
 *
 * <p>Limite: o comando do outro plugin não informa se funcionou. O débito só é feito depois de conferir
 * o saldo, mas não há como confirmar o resultado. Tudo roda na thread principal.</p>
 */
public final class CommandProvider implements EconomyProvider {

    private final String id;
    private final String display;
    private final String balancePlaceholder;
    private final String giveCommand;
    private final String takeCommand;

    public CommandProvider(String id, String display, String balancePlaceholder, String giveCommand, String takeCommand) {
        this.id = id.toLowerCase(Locale.ROOT);
        this.display = display;
        this.balancePlaceholder = balancePlaceholder;
        this.giveCommand = giveCommand;
        this.takeCommand = takeCommand;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String source() {
        return "comandos (economies.yml)";
    }

    @Override
    public String currencyName() {
        return display;
    }

    @Override
    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                && giveCommand != null && !giveCommand.isEmpty()
                && takeCommand != null && !takeCommand.isEmpty();
    }

    @Override
    public boolean threadSafe() {
        return false;
    }

    @Override
    public double balance(OfflinePlayer player) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method m = papi.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            String raw = String.valueOf(m.invoke(null, player, balancePlaceholder));
            String clean = raw.replaceAll("[^0-9,.\\-]", "");
            if (clean.isEmpty()) {
                return 0D;
            }
            // 1.234,56 (pt-BR) -> 1234.56 | 1,234.56 -> 1234.56
            int comma = clean.lastIndexOf(',');
            int dot = clean.lastIndexOf('.');
            if (comma > dot) {
                clean = clean.replace(".", "").replace(',', '.');
            } else {
                clean = clean.replace(",", "");
            }
            double v = Double.parseDouble(clean);
            return Double.isNaN(v) || Double.isInfinite(v) ? 0D : v;
        } catch (Throwable t) {
            return 0D;
        }
    }

    private String plain(double amount) {
        return java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    @Override
    public Result withdraw(OfflinePlayer player, double amount) {
        if (player.getName() == null) {
            return Result.ERROR;
        }
        if (balance(player) < amount) {
            return Result.INSUFFICIENT;
        }
        return run(takeCommand, player, amount) ? Result.OK : Result.ERROR;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return player.getName() != null && run(giveCommand, player, amount);
    }

    private boolean run(String template, OfflinePlayer player, double amount) {
        String cmd = template.replace("{player}", player.getName()).replace("{amount}", plain(amount));
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String format(double amount) {
        return Amounts.letter(amount) + " " + display;
    }
}
