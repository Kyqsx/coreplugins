package br.com.coreplugins.server.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, unique = true)
    private String email;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    /** PBKDF2; null para clientes criados só pela API admin (sem acesso ao site). */
    private String passwordHash;

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    /** {@code ADMIN} ou {@code CUSTOMER}; nulo (contas antigas) vale CUSTOMER. Nunca vem do corpo de uma requisição. */
    @Column(length = 16)
    private String role;
    /** Segredo TOTP em Base32. Só existe para administradores. */
    private String totpSecret;
    /** Só vira true depois que o admin provou, com um código, que o app de autenticação foi configurado. */
    private Boolean totpEnabled;
    /** Último passo de 30 s aceito: impede reutilizar o mesmo código (replay). */
    private Long totpLastStep;

    /**
     * "Versão de sessão": vai dentro de todo token emitido. Subir esse número (sair, trocar a senha, reset de admin)
     * invalida na hora todos os tokens anteriores da conta. Nulo (contas antigas) vale 0.
     */
    private Integer sessionVersion;

    /** Saldo da carteira em centavos. Só muda pelas consultas atômicas do CustomerRepository (credit/debit). Nulo vale 0. */
    private Long balanceCents;

    protected Customer() {
    }

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public Long getId() { return id; }
    @JsonIgnore
    public int getSessionVersion() { return sessionVersion == null ? 0 : sessionVersion; }
    /** Derruba todas as sessões desta conta (site e painel). */
    public void revokeSessions() { sessionVersion = getSessionVersion() + 1; }
    public long getBalanceCents() { return balanceCents == null ? 0L : balanceCents; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    @JsonIgnore
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role == null ? ROLE_CUSTOMER : role; }
    public void setRole(String role) { this.role = role; }
    @JsonIgnore
    public boolean isAdmin() { return ROLE_ADMIN.equals(role); }
    @JsonIgnore
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    @JsonIgnore
    public boolean isTotpEnabled() { return Boolean.TRUE.equals(totpEnabled); }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    @JsonIgnore
    public Long getTotpLastStep() { return totpLastStep; }
    public void setTotpLastStep(Long totpLastStep) { this.totpLastStep = totpLastStep; }
}
