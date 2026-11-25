package br.com.coreplugins.economias.economy;

import br.com.coreplugins.economias.storage.Storage;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Guarda o TOP de cada economia em memória; atualizado por tarefa assíncrona. */
public final class TopManager {

    private final Storage storage;
    private final CurrencyManager currencies;
    private final EconomyService service;
    private volatile Map<String, List<Storage.TopEntry>> cache = new HashMap<String, List<Storage.TopEntry>>();

    public TopManager(Storage storage, CurrencyManager currencies, EconomyService service) {
        this.storage = storage;
        this.currencies = currencies;
        this.service = service;
    }

    /** Chamar fora da thread principal. */
    public void refresh() {
        Map<String, List<Storage.TopEntry>> next = new HashMap<String, List<Storage.TopEntry>>(cache);
        for (Currency c : currencies.all()) {
            List<Storage.TopEntry> list = storage.top(c.getId(), c.getTopSize());
            if (list != null) {
                next.put(c.getId(), list);
            }
        }
        cache = next;
    }

    public List<Storage.TopEntry> get(String currencyId) {
        List<Storage.TopEntry> list = cache.get(currencyId);
        return list == null ? Collections.<Storage.TopEntry>emptyList() : list;
    }
}
