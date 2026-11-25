package br.com.coreplugins.server.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Um servidor Minecraft autorizado: a licença é o par IP:porta (modelo do yPlugins). */
@Entity
@Table(name = "authorized_servers", uniqueConstraints = @UniqueConstraint(columnNames = {"ip", "port"}))
public class AuthorizedServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false, length = 64)
    private String ip;
    @Column(nullable = false)
    private int port;
    private String label;
    @Column(nullable = false)
    private boolean active = true;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    private Instant lastSeenAt;

    protected AuthorizedServer() {
    }

    public AuthorizedServer(Long customerId, String ip, int port, String label) {
        this.customerId = customerId;
        this.ip = ip;
        this.port = port;
        this.label = label;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
