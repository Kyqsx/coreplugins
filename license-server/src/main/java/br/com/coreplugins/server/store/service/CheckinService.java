package br.com.coreplugins.server.store.service;

import br.com.coreplugins.server.store.model.Checkin;
import br.com.coreplugins.server.store.model.CheckinConfig;
import br.com.coreplugins.server.store.model.WalletTx;
import br.com.coreplugins.server.store.repo.CheckinConfigRepository;
import br.com.coreplugins.server.store.repo.CheckinRepository;
import br.com.coreplugins.server.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Check-in diário: um por cliente por dia (fuso de Brasília), com prêmio em saldo da carteira que cresce com os
 * dias seguidos. A configuração (ligado, prêmios por dia, o que acontece ao faltar) é feita pelo administrador.
 */
@Service
public class CheckinService {

    /** O "dia" do check-in vira à meia-noite de Brasília, não à do servidor. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public static final int MAX_DAYS = 31;
    public static final long MAX_REWARD_CENTS = 50_000;

    /** {@code rewardsCents}: prêmio de cada dia da sequência (o 1º item é o dia 1). */
    public record Config(boolean enabled, List<Long> rewardsCents, boolean resetOnMiss) {
    }

    /**
     * {@code streak}: dias seguidos até agora (conta hoje se já resgatou). {@code dayInCycle}: qual item de
     * {@code rewardsCents} é o de hoje (1..N), já resgatado ou não. {@code todayRewardCents}: o prêmio dele.
     */
    public record View(boolean enabled, boolean claimedToday, int streak, int dayInCycle,
                       List<Long> rewardsCents, long todayRewardCents) {
    }

    public record Claimed(long rewardCents, int streak, int dayInCycle, long balanceCents) {
    }

    private final CheckinConfigRepository configs;
    private final CheckinRepository checkins;
    private final WalletService wallet;

    public CheckinService(CheckinConfigRepository configs, CheckinRepository checkins, WalletService wallet) {
        this.configs = configs;
        this.checkins = checkins;
        this.wallet = wallet;
    }

    public Config config() {
        CheckinConfig c = configs.findById(CheckinConfig.ID).orElseGet(CheckinConfig::new);
        return new Config(c.isEnabled(), parse(c.getRewards()), c.isResetOnMiss());
    }

    @Transactional
    public Config updateConfig(boolean enabled, List<Long> rewardsCents, boolean resetOnMiss) {
        if (rewardsCents == null || rewardsCents.isEmpty() || rewardsCents.size() > MAX_DAYS) {
            throw ApiException.bad("Informe de 1 a " + MAX_DAYS + " dias de prêmio.");
        }
        StringBuilder sb = new StringBuilder();
        for (Long v : rewardsCents) {
            if (v == null || v < 0 || v > MAX_REWARD_CENTS) {
                throw ApiException.bad("Cada prêmio precisa estar entre R$ 0,00 e R$ " + (MAX_REWARD_CENTS / 100) + ",00.");
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        CheckinConfig c = configs.findById(CheckinConfig.ID).orElseGet(CheckinConfig::new);
        c.setEnabled(enabled);
        c.setRewards(sb.toString());
        c.setResetOnMiss(resetOnMiss);
        configs.save(c);
        return config();
    }

    public View view(Long customerId) {
        Config cfg = config();
        LocalDate today = LocalDate.now(ZONE);
        Optional<Checkin> last = checkins.findTopByCustomerIdOrderByDayDesc(customerId);
        boolean claimed = last.isPresent() && !last.get().getDay().isBefore(today);
        int streak = currentStreak(last, today, cfg.resetOnMiss());
        int n = cfg.rewardsCents().size();
        int day = claimed ? (streak - 1) % n + 1 : streak % n + 1;
        return new View(cfg.enabled(), claimed, streak, day, cfg.rewardsCents(), cfg.rewardsCents().get(day - 1));
    }

    /**
     * Resgata o check-in de hoje. O registro é gravado primeiro: se dois cliques chegarem juntos, o segundo bate na
     * restrição única e a transação inteira (inclusive o crédito) é desfeita; o controller transforma isso em
     * "já fez o check-in".
     */
    @Transactional
    public Claimed claim(Long customerId) {
        Config cfg = config();
        if (!cfg.enabled()) {
            throw ApiException.bad("O check-in diário está desativado no momento.");
        }
        LocalDate today = LocalDate.now(ZONE);
        Optional<Checkin> last = checkins.findTopByCustomerIdOrderByDayDesc(customerId);
        if (last.isPresent() && !last.get().getDay().isBefore(today)) {
            throw ApiException.bad("Você já fez o check-in de hoje. Volte amanhã!");
        }
        int streak = currentStreak(last, today, cfg.resetOnMiss()) + 1;
        int day = (streak - 1) % cfg.rewardsCents().size() + 1;
        long reward = cfg.rewardsCents().get(day - 1);
        checkins.saveAndFlush(new Checkin(customerId, today, streak, reward));
        if (reward > 0) {
            wallet.credit(customerId, reward, WalletTx.CHECKIN, "Check-in diário (dia " + streak + ")", null);
        }
        return new Claimed(reward, streak, day, wallet.balance(customerId));
    }

    /** Sequência vigente: continua se o último check-in foi hoje ou ontem; senão zera (ou segue, se a opção estiver desligada). */
    private static int currentStreak(Optional<Checkin> last, LocalDate today, boolean resetOnMiss) {
        if (last.isEmpty()) {
            return 0;
        }
        LocalDate d = last.get().getDay();
        if (!d.isBefore(today.minusDays(1))) {
            return last.get().getStreak();
        }
        return resetOnMiss ? 0 : last.get().getStreak();
    }

    private static List<Long> parse(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                try {
                    long v = Long.parseLong(part.trim());
                    if (v >= 0) {
                        out.add(v);
                    }
                } catch (NumberFormatException ignored) {
                    // valor corrompido: ignora
                }
            }
        }
        if (out.isEmpty()) {
            out.add(0L);
        }
        return out;
    }
}
