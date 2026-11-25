package br.com.coreplugins.spawners.economy;

import org.bukkit.OfflinePlayer;

/** Uma moeda utilizável pelo plugin (coreEconomias, Vault, PlayerPoints, comandos...). */
public interface EconomyProvider {

    enum Result { OK, INSUFFICIENT, ERROR }

    /** Nome canônico (minúsculo): o que se escreve em {@code currency:} nos arquivos. */
    String id();

    /** Origem, para mostrar em /spawners economies (ex.: "coreEconomias"). */
    String source();

    /** Nome da moeda para mensagens (ex.: "coins"). */
    String currencyName();

    boolean isAvailable();

    /**
     * {@code true} se {@link #withdraw} e {@link #deposit} podem rodar fora da thread principal.
     * O plugin usa isso para não travar o servidor enquanto o banco confirma um débito.
     */
    boolean threadSafe();

    double balance(OfflinePlayer player);

    /** Débito. Pode bloquear (confirmação no banco): chame fora da thread principal quando {@link #threadSafe()}. */
    Result withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);

    /** Valor + nome da moeda, como o próprio plugin de economia mostraria (ex.: "1.5K coins"). */
    String format(double amount);
}
