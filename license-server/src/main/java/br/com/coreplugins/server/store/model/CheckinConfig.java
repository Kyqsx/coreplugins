package br.com.coreplugins.server.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Configuração do check-in diário (uma única linha, id = 1). {@code rewards} é a lista de prêmios em centavos,
 * separados por vírgula: o item N é o prêmio do N-º dia seguido; depois do último a sequência de prêmios recomeça.
 * Vem desligado por padrão: o administrador liga e define os valores no painel.
 */
@Entity
@Table(name = "checkin_config")
public class CheckinConfig {

    public static final long ID = 1L;

    @Id
    private Long id = ID;
    @Column(nullable = false)
    private boolean enabled = false;
    @Column(nullable = false, length = 400)
    private String rewards = "10,10,10,10,10,10,50";
    /** true: faltar um dia zera a sequência; false: ela só deixa de crescer e continua de onde parou. */
    @Column(nullable = false)
    private boolean resetOnMiss = true;

    public CheckinConfig() {
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRewards() { return rewards; }
    public void setRewards(String rewards) { this.rewards = rewards; }
    public boolean isResetOnMiss() { return resetOnMiss; }
    public void setResetOnMiss(boolean resetOnMiss) { this.resetOnMiss = resetOnMiss; }
}
