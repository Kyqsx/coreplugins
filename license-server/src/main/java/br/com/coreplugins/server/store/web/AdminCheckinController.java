package br.com.coreplugins.server.store.web;

import br.com.coreplugins.server.store.service.CheckinService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Configuração do check-in diário no painel de administração (protegida pela sessão de admin, como todo /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
public class AdminCheckinController {

    public record ConfigReq(Boolean enabled, List<Long> rewardsCents, Boolean resetOnMiss) {
    }

    private final CheckinService checkin;

    public AdminCheckinController(CheckinService checkin) {
        this.checkin = checkin;
    }

    @GetMapping("/checkin")
    public CheckinService.Config get() {
        return checkin.config();
    }

    @PutMapping("/checkin")
    public CheckinService.Config put(@RequestBody ConfigReq r) {
        return checkin.updateConfig(Boolean.TRUE.equals(r.enabled()), r.rewardsCents(),
                r.resetOnMiss() == null || r.resetOnMiss());
    }
}
