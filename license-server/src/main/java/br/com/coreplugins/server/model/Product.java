package br.com.coreplugins.server.model;

import jakarta.persistence.*;

/** Um produto vendido (ex.: "yclans"). O id é o slug usado em /api/v1/products/{id}. */
@Entity
@Table(name = "products")
public class Product {
    @Id
    @Column(length = 64)
    private String id;
    @Column(nullable = false)
    private String name;
    @Column(length = 1000)
    private String description;
    @Column(nullable = false)
    private boolean active = true;
    /** Versão que novos clientes recebem. Null enquanto nenhum jar foi enviado. */
    private String latestVersion;
    /** Preço em centavos. Null/0 = fora da loja. */
    private Integer priceCents;
    private String category;
    /** Comando principal exibido na loja (ex.: /minas). */
    private String command;
    /** ID (11 caracteres) do vídeo de showcase no YouTube. Guarda só o ID, nunca a URL digitada. Null = sem vídeo. */
    @Column(length = 11)
    private String youtubeId;

    protected Product() {
    }

    public Product(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getLatestVersion() { return latestVersion; }
    public void setLatestVersion(String latestVersion) { this.latestVersion = latestVersion; }
    public int getPriceCents() { return priceCents == null ? 0 : priceCents; }
    public void setPriceCents(Integer priceCents) { this.priceCents = priceCents; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getYoutubeId() { return youtubeId; }
    public void setYoutubeId(String youtubeId) { this.youtubeId = youtubeId; }
}
