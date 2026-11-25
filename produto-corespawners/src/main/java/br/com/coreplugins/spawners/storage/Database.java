package br.com.coreplugins.spawners.storage;

import br.com.coreplugins.spawners.model.PlayerStats;
import br.com.coreplugins.spawners.model.Spawner;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** SQLite ou MySQL, com uma conexão só e trava própria (as gravações vêm de uma thread única). */
public final class Database {

    private final boolean mysql;
    private final String url;
    private final Properties props = new Properties();
    private final String spawners;
    private final String drops;
    private final String friends;
    private final String boosters;
    private final String stats;
    private final String sales;
    private final Object lock = new Object();
    private Connection connection;
    private Driver driver;

    public Database(File dataFolder, ConfigurationSection db) {
        this.mysql = "MYSQL".equalsIgnoreCase(db.getString("type", "SQLITE"));
        String prefix = db.getString("table-prefix", "spw_").replaceAll("[^A-Za-z0-9_]", "");
        this.spawners = prefix + "spawners";
        this.drops = prefix + "drops";
        this.friends = prefix + "friends";
        this.boosters = prefix + "boosters";
        this.stats = prefix + "stats";
        this.sales = prefix + "sales";
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
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + spawners + " (id BIGINT NOT NULL PRIMARY KEY,"
                        + " type VARCHAR(64) NOT NULL, owner VARCHAR(36) NOT NULL, owner_name VARCHAR(32) NOT NULL,"
                        + " world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                        + " stack INT NOT NULL, mobs BIGINT NOT NULL, enabled INT NOT NULL, is_public INT NOT NULL,"
                        + " hologram INT NOT NULL, autosell INT NOT NULL, drops_level INT NOT NULL, speed_level INT NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + drops + " (spawner_id BIGINT NOT NULL,"
                        + " drop_key VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, PRIMARY KEY (spawner_id, drop_key))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + friends + " (spawner_id BIGINT NOT NULL,"
                        + " friend VARCHAR(36) NOT NULL, friend_name VARCHAR(32) NOT NULL, PRIMARY KEY (spawner_id, friend))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + boosters + " (spawner_id BIGINT NOT NULL,"
                        + " kind VARCHAR(16) NOT NULL, booster_id VARCHAR(64) NOT NULL, expires BIGINT NOT NULL,"
                        + " PRIMARY KEY (spawner_id, kind))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + stats + " (player VARCHAR(36) NOT NULL PRIMARY KEY,"
                        + " name VARCHAR(32) NOT NULL, kills BIGINT NOT NULL, sold_items BIGINT NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + sales + " (player VARCHAR(36) NOT NULL,"
                        + " currency VARCHAR(64) NOT NULL, total DOUBLE NOT NULL, PRIMARY KEY (player, currency))");
            }
            // Bancos criados na primeira versão não têm a coluna do ovo: acrescenta (erro = já existe).
            try (Statement st = conn().createStatement()) {
                st.executeUpdate("ALTER TABLE " + spawners + " ADD COLUMN egg INT NOT NULL DEFAULT 1");
            } catch (SQLException ignored) {
                // coluna já existe
            }
            try (Statement st = conn().createStatement()) {
                st.executeUpdate("ALTER TABLE " + spawners + " ADD COLUMN facing INT NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // coluna já existe
            }
        }
    }

    public List<Spawner> loadAll() throws SQLException {
        synchronized (lock) {
            List<Spawner> out = new ArrayList<Spawner>();
            Connection c = conn();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + spawners)) {
                while (rs.next()) {
                    Spawner s = new Spawner(rs.getLong("id"), rs.getString("type"), UUID.fromString(rs.getString("owner")),
                            rs.getString("owner_name"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"),
                            rs.getInt("z"), rs.getInt("stack"));
                    s.mobs = rs.getLong("mobs");
                    s.enabled = rs.getInt("enabled") != 0;
                    s.isPublic = rs.getInt("is_public") != 0;
                    s.hologram = rs.getInt("hologram") != 0;
                    s.autosell = rs.getInt("autosell") != 0;
                    s.egg = rs.getInt("egg") != 0;
                    s.facing = rs.getInt("facing");
                    s.dropsLevel = rs.getInt("drops_level");
                    s.speedLevel = rs.getInt("speed_level");
                    out.add(s);
                }
            }
            java.util.Map<Long, Spawner> byId = new java.util.HashMap<Long, Spawner>();
            for (Spawner s : out) {
                byId.put(s.id, s);
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + drops)) {
                while (rs.next()) {
                    Spawner s = byId.get(rs.getLong("spawner_id"));
                    if (s != null) {
                        s.drops.put(rs.getString("drop_key"), rs.getLong("amount"));
                    }
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + friends)) {
                while (rs.next()) {
                    Spawner s = byId.get(rs.getLong("spawner_id"));
                    if (s != null) {
                        s.friends.put(UUID.fromString(rs.getString("friend")), rs.getString("friend_name"));
                    }
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + boosters)) {
                while (rs.next()) {
                    Spawner s = byId.get(rs.getLong("spawner_id"));
                    if (s != null) {
                        s.boosts.put(rs.getString("kind"), new Spawner.Boost(rs.getString("booster_id"), rs.getLong("expires")));
                    }
                }
            }
            return out;
        }
    }

    /** Contadores dos jogadores (mobs mortos e vendas). */
    public List<PlayerStats> loadStats() throws SQLException {
        synchronized (lock) {
            Connection c = conn();
            java.util.Map<String, PlayerStats> byId = new java.util.HashMap<String, PlayerStats>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + stats)) {
                while (rs.next()) {
                    PlayerStats p = new PlayerStats(UUID.fromString(rs.getString("player")), rs.getString("name"));
                    p.load(rs.getLong("kills"), rs.getLong("sold_items"));
                    byId.put(rs.getString("player"), p);
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + sales)) {
                while (rs.next()) {
                    PlayerStats p = byId.get(rs.getString("player"));
                    if (p != null) {
                        p.loadSale(rs.getString("currency"), rs.getDouble("total"));
                    }
                }
            }
            return new ArrayList<PlayerStats>(byId.values());
        }
    }

    /** Grava os snapshots e apaga os removidos, em uma transação. */
    public void write(Collection<Spawner> snapshots, Collection<Long> deleted) throws SQLException {
        write(snapshots, deleted, new ArrayList<PlayerStats>());
    }

    /** Grava geradores, remoções e contadores dos jogadores na mesma transação. */
    public void write(Collection<Spawner> snapshots, Collection<Long> deleted, Collection<PlayerStats> playerStats)
            throws SQLException {
        synchronized (lock) {
            Connection c = conn();
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (Long id : deleted) {
                    delete(c, spawners, "id", id);
                    delete(c, drops, "spawner_id", id);
                    delete(c, friends, "spawner_id", id);
                    delete(c, boosters, "spawner_id", id);
                }
                for (Spawner s : snapshots) {
                    save(c, s);
                }
                for (PlayerStats ps : playerStats) {
                    saveStats(c, ps);
                }
                c.commit();
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // a conexão será refeita
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(auto);
                } catch (SQLException ignored) {
                    // idem
                }
            }
        }
    }

    private void delete(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE " + column + " = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private void saveStats(Connection c, PlayerStats ps) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("REPLACE INTO " + stats
                + " (player, name, kills, sold_items) VALUES (?,?,?,?)")) {
            st.setString(1, ps.id.toString());
            st.setString(2, ps.name());
            st.setLong(3, ps.kills());
            st.setLong(4, ps.soldItems());
            st.executeUpdate();
        }
        try (PreparedStatement st = c.prepareStatement("REPLACE INTO " + sales + " (player, currency, total) VALUES (?,?,?)")) {
            for (Map.Entry<String, Double> e : ps.salesCopy().entrySet()) {
                st.setString(1, ps.id.toString());
                st.setString(2, e.getKey());
                st.setDouble(3, e.getValue());
                st.addBatch();
            }
            st.executeBatch();
        }
    }

    /** Descrição curta para o /spawners debug. */
    public String describe() {
        return mysql ? "MYSQL" : "SQLITE";
    }

    private void save(Connection c, Spawner s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("REPLACE INTO " + spawners
                + " (id, type, owner, owner_name, world, x, y, z, stack, mobs, enabled, is_public, hologram, autosell,"
                + " drops_level, speed_level, egg, facing) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, s.id);
            ps.setString(2, s.typeId);
            ps.setString(3, s.owner.toString());
            ps.setString(4, s.ownerName == null ? "?" : s.ownerName);
            ps.setString(5, s.world);
            ps.setInt(6, s.x);
            ps.setInt(7, s.y);
            ps.setInt(8, s.z);
            ps.setInt(9, s.stack);
            ps.setLong(10, s.mobs);
            ps.setInt(11, s.enabled ? 1 : 0);
            ps.setInt(12, s.isPublic ? 1 : 0);
            ps.setInt(13, s.hologram ? 1 : 0);
            ps.setInt(14, s.autosell ? 1 : 0);
            ps.setInt(15, s.dropsLevel);
            ps.setInt(16, s.speedLevel);
            ps.setInt(17, s.egg ? 1 : 0);
            ps.setInt(18, s.facing);
            ps.executeUpdate();
        }
        delete(c, drops, "spawner_id", s.id);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + drops + " (spawner_id, drop_key, amount) VALUES (?,?,?)")) {
            for (Map.Entry<String, Long> e : s.drops.entrySet()) {
                if (e.getValue() > 0) {
                    ps.setLong(1, s.id);
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        delete(c, boosters, "spawner_id", s.id);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + boosters + " (spawner_id, kind, booster_id, expires) VALUES (?,?,?,?)")) {
            for (Map.Entry<String, Spawner.Boost> e : s.boosts.entrySet()) {
                ps.setLong(1, s.id);
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue().boosterId);
                ps.setLong(4, e.getValue().expiresAt);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        delete(c, friends, "spawner_id", s.id);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + friends + " (spawner_id, friend, friend_name) VALUES (?,?,?)")) {
            for (Map.Entry<UUID, String> e : s.friends.entrySet()) {
                ps.setLong(1, s.id);
                ps.setString(2, e.getKey().toString());
                ps.setString(3, e.getValue() == null ? "?" : e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
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
                // reconecta
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
                Database.class.getClassLoader()
        };
        for (String n : names) {
            for (ClassLoader cl : loaders) {
                try {
                    driver = (Driver) Class.forName(n, true, cl).getDeclaredConstructor().newInstance();
                    return driver;
                } catch (Throwable ignored) {
                    // tenta o próximo
                }
            }
        }
        throw new SQLException("Driver JDBC não encontrado (" + (mysql ? "MySQL" : "SQLite") + ") neste servidor.");
    }
}
