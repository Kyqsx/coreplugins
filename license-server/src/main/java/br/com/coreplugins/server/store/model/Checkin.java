package br.com.coreplugins.server.store.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/** Um check-in feito. A restrição única (cliente, dia) é o que impede resgatar duas vezes no mesmo dia, mesmo com cliques simultâneos. */
@Entity
@Table(name = "checkins", uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "day"}))
public class Checkin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private LocalDate day;
    /** Quantos dias seguidos, contando este. */
    @Column(nullable = false)
    private int streak;
    @Column(name = "reward_cents", nullable = false)
    private long rewardCents;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Checkin() {
    }

    public Checkin(Long customerId, LocalDate day, int streak, long rewardCents) {
        this.customerId = customerId;
        this.day = day;
        this.streak = streak;
        this.rewardCents = rewardCents;
    }

    public Long getCustomerId() { return customerId; }
    public LocalDate getDay() { return day; }
    public int getStreak() { return streak; }
    public long getRewardCents() { return rewardCents; }
}
