package br.com.coreplugins.economias.storage;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistência via JDBC (SQLite ou MySQL) com uma única conexão sincronizada.
 * Usa o driver que já vem no servidor; por isso o Driver é instanciado direto,
 * sem passar pelo DriverManager (que não enxerga classes de outro ClassLoader).
 */
public final class Storage {

    public static final class TopEntry {
        public final String name;
        public final double balance;

        public TopEntry(String name, double balance) {
            this.name = name;
            this.balance = balance;
        }
    }

    public static final class PlayerRef {
        public final UUID uuid;
        public final String name;

        public PlayerRef(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }


    /**
     * Uma variação de saldo. ADD soma {@code value} (negativo = remover) ao que estiver no banco;
     * SET grava o valor absoluto. {@code def} é o saldo inicial da economia, usado só se a linha não existir.
     */
    public static final class Op {
        public static final int ADD = 0;
        public static final int SET = 1;

        public final UUID uuid;
        public final String currency;
        public final int type;
        public final double value;
        public final double def;

        public Op(UUID uuid, String currency, int type, double value, double def) {
            this.uuid = uuid;
            this.currency = currency;
            this.type = type;
            this.value = value;
            this.def = def;
        }
    }

    private final Logger logger;
    private final boolean mysql;
    private final String url;
    private final Properties props = new Properties();
    private final String playersTable;
    private final String balancesTable;
    private final String opsTable;
    private final Object lock = new Object();
    private Connection connection;
    private Driver driver;

    public Storage(Logger logger, File dataFolder, ConfigurationSection db) {
        this.logger = logger;
        this.mysql = "MYSQL".equalsIgnoreCase(db.getString("type", "SQLITE"));
        String prefix = db.getString("table-prefix", "eco_").replaceAll("[^A-Za-z0-9_]", "");
        this.playersTable = prefix + "players";
        this.balancesTable = prefix + "balances";
        this.opsTable = prefix + "ops";
        if (mysql) {
            ConfigurationSection my = db.getConfigurationSection("mysql");
            if (my == null) {
                throw new IllegalStateException("Seção database.mysql ausente no config.yml");
            }
            String params = my.getString("parameters", "");
            this.url = "jdbc:mysql://" + my.getString("host", "localhost") + ":" + my.getInt("port", 3306)
                    + "/" + my.getString("database", "rankup") + (params.isEmpty() ? "" : "?" + params);
            props.setProperty("user", my.getString("username", "root"));
            props.setProperty("password", my.getString("password", ""));
        } else {
            this.url = "jdbc:sqlite:" + new File(dataFolder, db.getString("sqlite-file", "database.db")).getAbsolutePath();
        }
    }

    public void init() throws SQLException {
        synchronized (lock) {
            try (Statement st = conn().createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + playersTable
                        + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + balancesTable
                        + " (uuid VARCHAR(36) NOT NULL, currency VARCHAR(64) NOT NULL, balance DOUBLE NOT NULL,"
                        + " PRIMARY KEY (uuid, currency))");
                // Registro das operações já aplicadas: impede aplicar duas vezes a mesma variação
                // (retentativa após falha de rede no commit, reenvio do arquivo de pendências).
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + opsTable
                        + " (id VARCHAR(36) NOT NULL PRIMARY KEY, created BIGINT NOT NULL)");
            }
        }
    }

    /** Lança exceção em caso de falha: quem chama NUNCA deve tratar falha como "sem saldo". */
    public Map<String, Double> loadBalances(UUID uuid) throws SQLException {
        synchronized (lock) {
            Map<String, Double> map = new HashMap<String, Double>();
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT currency, balance FROM " + balancesTable + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getDouble(2));
                    }
                }
            }
            return map;
        }
    }

    public enum DebitResult { OK, INSUFFICIENT }

    /**
     * Débito CONFIRMADO no banco: só desconta se o saldo no banco for suficiente (UPDATE condicional),
     * então dois servidores nunca gastam o mesmo saldo. Com {@code to} != null, o crédito ao destinatário
     * vai na mesma transação. Mesmo id já aplicado antes = OK sem repetir. Lança exceção em falha de banco.
     */
    public DebitResult debit(String id, UUID from, String currency, double amount, double def, UUID to)
            throws SQLException {
        synchronized (lock) {
            Connection c = conn();
            boolean oldAuto = c.getAutoCommit();
            try {
                c.setAutoCommit(false);
                if (alreadyApplied(c, id)) {
                    c.rollback();
                    return DebitResult.OK;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + opsTable + " (id, created) VALUES (?, ?)")) {
                    ps.setString(1, id);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                // Garante a linha do pagador (saldo inicial) para o UPDATE condicional enxergá-la.
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + balancesTable + " (uuid, currency, balance) VALUES (?, ?, ?)")) {
                    ps.setString(1, from.toString());
                    ps.setString(2, currency);
                    ps.setDouble(3, def);
                    ps.executeUpdate();
                } catch (SQLException exists) {
                    // já existe (caso normal): segue. Se o erro for outro, o UPDATE abaixo também falha.
                }
                int rows;
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + balancesTable
                        + " SET balance = balance - ? WHERE uuid = ? AND currency = ? AND balance >= ?")) {
                    ps.setDouble(1, amount);
                    ps.setString(2, from.toString());
                    ps.setString(3, currency);
                    ps.setDouble(4, amount);
                    rows = ps.executeUpdate();
                }
                if (rows == 0) {
                    c.rollback();
                    return DebitResult.INSUFFICIENT;
                }
                if (to != null) {
                    applyOne(c, new Op(to, currency, Op.ADD, amount, def));
                }
                c.commit();
                return DebitResult.OK;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // a conexão será recriada na próxima chamada
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(oldAuto);
                } catch (SQLException ignored) {
                    // idem
                }
            }
        }
    }

    /** O grupo {@code id} já foi aplicado? Usado para resolver dúvida após falha no commit. */
    public boolean wasApplied(String id) throws SQLException {
        synchronized (lock) {
            Connection c = conn();
            return alreadyApplied(c, id);
        }
    }

    private boolean alreadyApplied(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + opsTable + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Grava o nome do jogador (usado só para exibir o TOP e para achar jogadores offline). */
    public boolean saveName(UUID uuid, String name) {
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "REPLACE INTO " + playersTable + " (uuid, name) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Falha ao salvar o nome de " + uuid, e);
                return false;
            }
        }
    }

    /**
     * Aplica um grupo de variações numa única transação (tudo ou nada). {@code id} identifica o grupo:
     * se já tiver sido aplicado antes, nada é feito. Lança exceção se falhar; quem chama deve tentar de novo
     * com o MESMO id, o que é seguro.
     *
     * @return true se aplicou agora, false se o grupo já constava como aplicado
     */
    public boolean apply(String id, List<Op> ops) throws SQLException {
        synchronized (lock) {
            Connection c = conn();
            boolean oldAuto = c.getAutoCommit();
            try {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + opsTable + " WHERE id = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            c.rollback();
                            return false;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + opsTable + " (id, created) VALUES (?, ?)")) {
                    ps.setString(1, id);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                for (Op op : ops) {
                    applyOne(c, op);
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // a conexão será recriada na próxima chamada
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(oldAuto);
                } catch (SQLException ignored) {
                    // idem
                }
            }
        }
    }

    private void applyOne(Connection c, Op op) throws SQLException {
        String update = op.type == Op.ADD
                ? "UPDATE " + balancesTable + " SET balance = balance + ? WHERE uuid = ? AND currency = ?"
                : "UPDATE " + balancesTable + " SET balance = ? WHERE uuid = ? AND currency = ?";
        if (runUpdate(c, update, op) > 0) {
            return;
        }
        // Linha ainda não existe: cria. Se outro servidor criou no meio tempo, o INSERT falha por chave
        // duplicada e então basta repetir o UPDATE.
        double initial = op.type == Op.ADD ? op.def + op.value : op.value;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + balancesTable + " (uuid, currency, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, op.uuid.toString());
            ps.setString(2, op.currency);
            ps.setDouble(3, initial);
            ps.executeUpdate();
        } catch (SQLException dup) {
            runUpdate(c, update, op); // se o erro não era duplicidade, este UPDATE também falha e propaga
        }
    }

    private int runUpdate(Connection c, String sql, Op op) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, op.value);
            ps.setString(2, op.uuid.toString());
            ps.setString(3, op.currency);
            return ps.executeUpdate();
        }
    }

    /** Remove do registro as operações antigas (já não há como serem reenviadas). */
    public void pruneOps(long olderThanMillis) {
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + opsTable + " WHERE created < ?")) {
                ps.setLong(1, System.currentTimeMillis() - olderThanMillis);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Falha ao limpar o registro de operações", e);
            }
        }
    }

    /** Devolve null se não achar ou se houver erro. */
    public PlayerRef findByName(String name) {
        synchronized (lock) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT uuid, name FROM " + playersTable + " WHERE LOWER(name) = LOWER(?)")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Falha ao procurar jogador " + name, e);
            }
            return null;
        }
    }

    /** Devolve null em caso de erro (para o chamador manter o TOP anterior). */
    public List<TopEntry> top(String currency, int limit) {
        synchronized (lock) {
            List<TopEntry> list = new ArrayList<TopEntry>();
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT p.name, b.balance FROM " + balancesTable + " b LEFT JOIN " + playersTable
                            + " p ON p.uuid = b.uuid WHERE b.currency = ? ORDER BY b.balance DESC LIMIT ?")) {
                ps.setString(1, currency);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String n = rs.getString(1);
                        list.add(new TopEntry(n == null ? "?" : n, rs.getDouble(2)));
                    }
                }
                return list;
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Falha ao montar o TOP de " + currency, e);
                return null;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // encerrando
                }
                connection = null;
            }
        }
    }

    private Connection conn() throws SQLException {
        if (connection != null) {
            try {
                if (!connection.isClosed() && (!mysql || connection.isValid(2))) {
                    return connection;
                }
            } catch (SQLException ignored) {
                // cai para reconexão
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // já inválida
            }
        }
        Connection c = driver().connect(url, props);
        if (c == null) {
            throw new SQLException("O driver recusou a URL " + url);
        }
        connection = c;
        return c;
    }

    private Driver driver() throws SQLException {
        if (driver != null) {
            return driver;
        }
        String[] names = mysql
                ? new String[]{"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"}
                : new String[]{"org.sqlite.JDBC"};
        ClassLoader[] loaders = {
                Bukkit.getServer().getClass().getClassLoader(),
                Bukkit.class.getClassLoader(),
                Storage.class.getClassLoader()
        };
        for (String name : names) {
            for (ClassLoader cl : loaders) {
                try {
                    driver = (Driver) Class.forName(name, true, cl).newInstance();
                    return driver;
                } catch (Throwable ignored) {
                    // tenta o próximo
                }
            }
        }
        throw new SQLException("Driver JDBC não encontrado no servidor (" + names[0] + ").");
    }
}
