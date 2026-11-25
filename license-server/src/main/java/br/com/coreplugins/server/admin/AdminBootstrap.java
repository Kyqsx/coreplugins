package br.com.coreplugins.server.admin;

import br.com.coreplugins.server.config.LicenseProperties;
import br.com.coreplugins.server.model.Customer;
import br.com.coreplugins.server.repo.CustomerRepository;
import br.com.coreplugins.server.store.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Cria (ou promove) o primeiro administrador a partir de ADMIN_EMAIL / ADMIN_PASSWORD na inicialização.
 * Depois do primeiro login e do cadastro do 2FA, tire ADMIN_PASSWORD do ambiente: ela só é lida para criar a conta.
 * Esqueceu a senha ou perdeu o celular? Suba uma vez com ADMIN_RESET=true (e ADMIN_EMAIL/ADMIN_PASSWORD): a senha é
 * trocada e o 2FA é zerado para recadastrar. Depois remova ADMIN_RESET.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MIN_PASSWORD = 12;

    private final CustomerRepository customers;
    private final LicenseProperties props;

    public AdminBootstrap(CustomerRepository customers, LicenseProperties props) {
        this.customers = customers;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.adminRequire2fa()) {
            log.warn("ADMIN_REQUIRE_2FA=false: o painel admin entra só com e-mail e senha. Use apenas em teste local.");
        }
        String email = props.adminEmail() == null ? "" : props.adminEmail().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            boolean any = customers.findAll().stream().anyMatch(Customer::isAdmin);
            if (!any) {
                log.warn("Nenhum administrador cadastrado: defina ADMIN_EMAIL e ADMIN_PASSWORD para criar o primeiro.");
            }
            return;
        }
        Customer c = customers.findByEmailIgnoreCase(email).orElse(null);
        String password = props.adminPassword();
        if (c == null) {
            requireStrong(password);
            c = new Customer("Administrador", email);
            c.setPasswordHash(PasswordHasher.hash(password));
            c.setRole(Customer.ROLE_ADMIN);
            customers.save(c);
            log.info("Administrador {} criado. Entre no painel para cadastrar o app de autenticação (2FA).", email);
            return;
        }
        boolean changed = false;
        if (!c.isAdmin()) {
            c.setRole(Customer.ROLE_ADMIN);
            changed = true;
            log.info("Conta {} promovida a administrador.", email);
        }
        if (c.getPasswordHash() == null || props.adminReset()) {
            requireStrong(password);
            c.setPasswordHash(PasswordHasher.hash(password));
            c.revokeSessions();
            changed = true;
        }
        if (props.adminReset()) {
            c.setTotpSecret(null);
            c.setTotpEnabled(false);
            c.setTotpLastStep(null);
            changed = true;
            log.warn("ADMIN_RESET: senha e 2FA de {} foram zerados. Remova ADMIN_RESET e ADMIN_PASSWORD do ambiente.", email);
        }
        if (changed) {
            customers.save(c);
        }
    }

    private static void requireStrong(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalStateException("ADMIN_PASSWORD precisa ter ao menos " + MIN_PASSWORD + " caracteres.");
        }
    }
}
