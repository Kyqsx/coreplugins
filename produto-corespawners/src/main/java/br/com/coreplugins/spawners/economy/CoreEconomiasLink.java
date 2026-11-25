package br.com.coreplugins.spawners.economy;

import br.com.coreplugins.api.ProductPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ponte com o produto coreEconomias.
 *
 * <p>Cada produto da CorePlugins roda no seu próprio ClassLoader, então as classes do coreEconomias não
 * são visíveis aqui. Por isso o acesso é por reflexão: o produto é localizado no registro estático da
 * {@link ProductPlugin} (que vem do loader e é compartilhado) e os métodos públicos do serviço de economia
 * ({@code EconomyService}) e do gerenciador de moedas ({@code CurrencyManager}) são chamados por nome.</p>
 *
 * <p>Nada é guardado por muito tempo: se o coreEconomias for recarregado ({@code /coreplugins reload}) ou
 * trocar de versão, o link se refaz sozinho na próxima chamada.</p>
 */
public final class CoreEconomiasLink {

    public static final String PRODUCT_NAME = "coreEconomias";

    private final Logger logger;
    private final boolean debug;

    private volatile ProductPlugin product;
    private volatile Object service;
    private volatile Object currencyManager;
    private volatile Method svcGet;
    private volatile Method svcAdd;
    private volatile Method svcTryRemove;
    private volatile Method mgrGet;
    private volatile Method mgrAll;
    private volatile Method curId;
    private volatile Method curFormat;
    private volatile Method curUnit;
    private volatile Method curPlural;
    private boolean warnedStructure;

    public CoreEconomiasLink(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    /** {@code true} se o coreEconomias está habilitado e já terminou de iniciar (serviço e moedas prontos). */
    public synchronized boolean ready() {
        ProductPlugin p = product;
        if (p != null && p.isEnabled() && service != null) {
            return true;
        }
        return bind();
    }

    private boolean bind() {
        product = null;
        service = null;
        currencyManager = null;
        ProductPlugin found = find();
        if (found == null) {
            return false;
        }
        try {
            Object svc = invokeNoArgs(found, "getService");
            Object mgr = invokeNoArgs(found, "getCurrencies");
            if (svc == null || mgr == null) {
                return false; // ainda iniciando (o coreEconomias começa 1 tick depois do onEnable)
            }
            Method[] sm = svc.getClass().getMethods();
            svcGet = method(sm, "get", 2, UUID.class);
            svcAdd = method(sm, "add", 3, UUID.class);
            svcTryRemove = method(sm, "tryRemove", 3, UUID.class);
            Method[] mm = mgr.getClass().getMethods();
            mgrGet = method(mm, "get", 1, String.class);
            mgrAll = method(mm, "all", 0, null);
            if (svcGet == null || svcAdd == null || svcTryRemove == null || mgrGet == null || mgrAll == null) {
                warnStructure("métodos esperados não encontrados no coreEconomias (versão incompatível?)");
                return false;
            }
            product = found;
            service = svc;
            currencyManager = mgr;
            return true;
        } catch (Throwable t) {
            warnStructure("falha ao ligar com o coreEconomias: " + t);
            if (debug) {
                logger.log(Level.WARNING, "Detalhes", t);
            }
            return false;
        }
    }

    private void warnStructure(String msg) {
        if (!warnedStructure) {
            warnedStructure = true;
            logger.warning("[economia] " + msg + ". Vou usar Vault/PlayerPoints se existirem.");
        }
    }

    /** Procura o produto no registro estático (campo privado INSTANCES da ProductPlugin). */
    private ProductPlugin find() {
        try {
            Field f = ProductPlugin.class.getDeclaredField("INSTANCES");
            f.setAccessible(true);
            Object map = f.get(null);
            if (!(map instanceof Map)) {
                return null;
            }
            for (Object v : ((Map<?, ?>) map).values()) {
                if (v instanceof ProductPlugin) {
                    ProductPlugin pp = (ProductPlugin) v;
                    if (PRODUCT_NAME.equalsIgnoreCase(pp.getName()) && pp.isEnabled()) {
                        return pp;
                    }
                }
            }
        } catch (Throwable t) {
            warnStructure("não consegui ler o registro de produtos do loader (" + t + ")");
        }
        return null;
    }


    /** Os métodos de Currency só podem ser resolvidos quando existe pelo menos uma moeda carregada. */
    private void ensureCurMethods(Object currency) {
        if (curId != null && curFormat != null && curUnit != null && curPlural != null) {
            return;
        }
        Method[] cm = currency.getClass().getMethods();
        curId = method(cm, "getId", 0, null);
        curFormat = method(cm, "format", 1, double.class);
        curUnit = method(cm, "unit", 1, double.class);
        curPlural = method(cm, "getPlural", 0, null);
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    private static Method method(Method[] methods, String name, int params, Class<?> firstParam) {
        for (Method m : methods) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != params) {
                continue;
            }
            if (firstParam != null && m.getParameterTypes()[0] != firstParam) {
                continue;
            }
            return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ moedas

    /** Ids (minúsculos) de todas as moedas do coreEconomias. Vazio se ele não estiver pronto. */
    public List<String> currencyIds() {
        List<String> ids = new ArrayList<String>();
        if (!ready()) {
            return ids;
        }
        try {
            Collection<?> all = (Collection<?>) mgrAll.invoke(currencyManager);
            if (all != null) {
                for (Object c : all) {
                    ensureCurMethods(c);
                    if (curId != null) {
                        ids.add(String.valueOf(curId.invoke(c)).toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Throwable t) {
            logError(t);
        }
        return ids;
    }

    /** Objeto Currency do coreEconomias, ou {@code null}. Sempre buscado de novo: o menu do coreEconomias pode recriar. */
    public Object currency(String id) {
        if (!ready()) {
            return null;
        }
        try {
            return mgrGet.invoke(currencyManager, id);
        } catch (Throwable t) {
            logError(t);
            return null;
        }
    }

    public double get(UUID player, Object currency) {
        try {
            Object r = svcGet.invoke(service, player, currency);
            return r instanceof Number ? ((Number) r).doubleValue() : 0D;
        } catch (Throwable t) {
            logError(t);
            return 0D;
        }
    }

    public boolean add(UUID player, Object currency, double amount) {
        try {
            return Boolean.TRUE.equals(svcAdd.invoke(service, player, currency, amount));
        } catch (Throwable t) {
            logError(t);
            return false;
        }
    }

    /** "OK", "INSUFFICIENT" ou "ERROR" (nome do enum EconomyService.Result). Pode bloquear até o banco confirmar. */
    public String tryRemove(UUID player, Object currency, double amount) {
        try {
            Object r = svcTryRemove.invoke(service, player, currency, amount);
            return r == null ? "ERROR" : ((Enum<?>) r).name();
        } catch (Throwable t) {
            logError(t);
            return "ERROR";
        }
    }

    public String format(Object currency, double amount) {
        try {
            ensureCurMethods(currency);
            String value = String.valueOf(curFormat.invoke(currency, amount));
            String unit = String.valueOf(curUnit.invoke(currency, amount));
            return value + " " + unit;
        } catch (Throwable t) {
            return String.valueOf(amount);
        }
    }

    public String plural(Object currency) {
        try {
            ensureCurMethods(currency);
            return String.valueOf(curPlural.invoke(currency));
        } catch (Throwable t) {
            return "";
        }
    }

    private void logError(Throwable t) {
        if (debug) {
            logger.log(Level.WARNING, "[economia] erro ao falar com o coreEconomias", t);
        }
    }
}
