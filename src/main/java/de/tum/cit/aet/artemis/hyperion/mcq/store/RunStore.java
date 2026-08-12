package de.tum.cit.aet.artemis.hyperion.mcq.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable state for a generation run, held in a single SQLite file.
 * <p>
 * One row per intended item, advancing through {@link ItemState}. All transitions are single atomic
 * statements, so a process that dies mid-item leaves the row in a claimed state that
 * {@link #releaseStaleClaims(String)} returns to its previous stable state. This store is the source of
 * truth; the JSONL run log is exported from it.
 */
public class RunStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);

    private final Connection connection;

    /**
     * Open or create the store at the given path.
     *
     * @param database SQLite file; parent directories must already exist
     * @throws IllegalStateException if the database cannot be opened or migrated
     */
    public RunStore(Path database) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            this.connection.setAutoCommit(true);
            migrate();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to open run store at " + database, e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA journal_mode=WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS run (
                        run_id TEXT PRIMARY KEY,
                        configuration_id TEXT NOT NULL,
                        manifest TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS item (
                        run_id TEXT NOT NULL,
                        configuration_id TEXT NOT NULL,
                        topic_key TEXT NOT NULL,
                        item_index INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        generation_attempts INTEGER NOT NULL DEFAULT 0,
                        filter_attempts INTEGER NOT NULL DEFAULT 0,
                        item_json TEXT,
                        provenance_json TEXT,
                        decision_json TEXT,
                        calls_json TEXT,
                        failure TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (run_id, configuration_id, topic_key, item_index)
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS item_by_state ON item (run_id, state)");
        }
    }

    /** Identifies one unit of work within a run. */
    public record ItemKey(String runId, String configurationId, String topicKey, int itemIndex) {
    }

    /**
     * A claimed unit of work.
     *
     * @param generatedItemJson    the stored item, present when the claim is for filtering
     * @param provenanceJson       the stored provenance, present when the claim is for filtering
     */
    public record Claim(ItemKey key, ItemState state, int generationAttempts, int filterAttempts, String generatedItemJson, String provenanceJson, String callsJson) {
    }

    /**
     * Register a run, or verify that an existing run was created with the same manifest.
     *
     * @param runId           run identifier
     * @param configurationId generator and filter pairing
     * @param manifest        serialised configuration snapshot used to detect changed settings on resume
     * @throws IllegalStateException if a run of this id exists with a different manifest
     */
    public synchronized void registerRun(String runId, String configurationId, String manifest) {
        Optional<String> existing = queryString("SELECT manifest FROM run WHERE run_id = ?", runId);
        if (existing.isPresent()) {
            if (!existing.get().equals(manifest)) {
                throw new IllegalStateException(
                        "Run " + runId + " was created with a different configuration. Resuming would mix settings within one dataset; start a new run instead.");
            }
            log.info("Resuming run {}", runId);
            return;
        }
        execute("INSERT INTO run (run_id, configuration_id, manifest, created_at) VALUES (?, ?, ?, ?)", runId, configurationId, manifest, Instant.now().toString());
        log.info("Registered run {}", runId);
    }

    /**
     * Create work rows for a run, ignoring any that already exist.
     *
     * @param keys units of work to enqueue
     * @return the number of rows newly created
     */
    public synchronized int enqueue(List<ItemKey> keys) {
        int created = 0;
        for (ItemKey key : keys) {
            created += execute("""
                    INSERT OR IGNORE INTO item (run_id, configuration_id, topic_key, item_index, state, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""", key.runId(), key.configurationId(), key.topicKey(), key.itemIndex(), ItemState.PENDING.name(), Instant.now().toString());
        }
        return created;
    }

    /**
     * Return rows claimed by a process that is no longer running to their previous stable state.
     *
     * @param runId run to recover
     * @return the number of rows released
     */
    public synchronized int releaseStaleClaims(String runId) {
        int generating = execute("UPDATE item SET state = ?, updated_at = ? WHERE run_id = ? AND state = ?", ItemState.PENDING.name(), Instant.now().toString(), runId,
                ItemState.GENERATING.name());
        int filtering = execute("UPDATE item SET state = ?, updated_at = ? WHERE run_id = ? AND state = ?", ItemState.GENERATED.name(), Instant.now().toString(), runId,
                ItemState.FILTERING.name());
        if (generating + filtering > 0) {
            log.info("Released {} stale claims for run {} ({} generating, {} filtering)", generating + filtering, runId, generating, filtering);
        }
        return generating + filtering;
    }

    /**
     * Atomically claim the next unit of work, preferring items awaiting filtering so that generated
     * items are completed before more are produced.
     *
     * @param runId run to claim from
     * @return the claim, or empty when no work remains
     */
    public synchronized Optional<Claim> claimNext(String runId) {
        return claim(runId, ItemState.GENERATED, ItemState.FILTERING).or(() -> claim(runId, ItemState.PENDING, ItemState.GENERATING));
    }

    private Optional<Claim> claim(String runId, ItemState from, ItemState to) {
        int updated = execute("""
                UPDATE item SET state = ?, updated_at = ?
                WHERE rowid = (SELECT rowid FROM item WHERE run_id = ? AND state = ? ORDER BY topic_key, item_index LIMIT 1)""", to.name(), Instant.now().toString(), runId,
                from.name());
        if (updated == 0) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT configuration_id, topic_key, item_index, generation_attempts, filter_attempts, item_json, provenance_json, calls_json
                FROM item WHERE run_id = ? AND state = ? ORDER BY updated_at DESC LIMIT 1""")) {
            statement.setString(1, runId);
            statement.setString(2, to.name());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                ItemKey key = new ItemKey(runId, rows.getString(1), rows.getString(2), rows.getInt(3));
                return Optional.of(new Claim(key, to, rows.getInt(4), rows.getInt(5), rows.getString(6), rows.getString(7), rows.getString(8)));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read claimed item for run " + runId, e);
        }
    }

    /**
     * Record a successful generation, moving the item to {@link ItemState#GENERATED}.
     *
     * @param key             the item
     * @param itemJson        serialised question
     * @param provenanceJson  serialised provenance
     * @param callsJson       serialised call records accumulated so far
     */
    public synchronized void recordGenerated(ItemKey key, String itemJson, String provenanceJson, String callsJson) {
        update(key, "state = ?, item_json = ?, provenance_json = ?, calls_json = ?, generation_attempts = generation_attempts + 1, failure = NULL",
                List.of(ItemState.GENERATED.name(), itemJson, provenanceJson, callsJson));
    }

    /**
     * Record a completed filter decision, moving the item to {@link ItemState#FILTERED}.
     *
     * @param key           the item
     * @param decisionJson  serialised decision
     * @param callsJson     serialised call records including the filter call
     */
    public synchronized void recordFiltered(ItemKey key, String decisionJson, String callsJson) {
        update(key, "state = ?, decision_json = ?, calls_json = ?, filter_attempts = filter_attempts + 1, failure = NULL",
                List.of(ItemState.FILTERED.name(), decisionJson, callsJson));
    }

    /**
     * Record a failed attempt, either returning the item for another attempt or failing it permanently.
     *
     * @param key       the item
     * @param stage     the state the item was claimed into
     * @param failure   failure category recorded for reporting
     * @param callsJson serialised call records including the failed call
     * @param retry     whether to return the item to its previous stable state for another attempt
     */
    public synchronized void recordFailure(ItemKey key, ItemState stage, String failure, String callsJson, boolean retry) {
        ItemState next;
        if (retry) {
            next = stage == ItemState.GENERATING ? ItemState.PENDING : ItemState.GENERATED;
        }
        else {
            next = stage == ItemState.GENERATING ? ItemState.FAILED_GENERATION : ItemState.FAILED_FILTER;
        }
        String attempts = stage == ItemState.GENERATING ? "generation_attempts = generation_attempts + 1" : "filter_attempts = filter_attempts + 1";
        update(key, "state = ?, failure = ?, calls_json = ?, " + attempts, List.of(next.name(), failure, callsJson));
    }

    private void update(ItemKey key, String assignments, List<Object> values) {
        List<Object> parameters = new ArrayList<>(values);
        parameters.add(Instant.now().toString());
        parameters.add(key.runId());
        parameters.add(key.configurationId());
        parameters.add(key.topicKey());
        parameters.add(key.itemIndex());
        execute("UPDATE item SET " + assignments + ", updated_at = ? WHERE run_id = ? AND configuration_id = ? AND topic_key = ? AND item_index = ?", parameters.toArray());
    }

    /**
     * Count items per state for a run.
     *
     * @param runId run to summarise
     * @return counts keyed by state name, in insertion order of the underlying query
     */
    public synchronized Map<String, Integer> stateCounts(String runId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT state, COUNT(*) FROM item WHERE run_id = ? GROUP BY state ORDER BY state")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    counts.put(rows.getString(1), rows.getInt(2));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to summarise run " + runId, e);
        }
        return counts;
    }

    /**
     * @param runId run to inspect
     * @return true when no item is pending, generated or claimed
     */
    public synchronized boolean isComplete(String runId) {
        Map<String, Integer> counts = stateCounts(runId);
        return List.of(ItemState.PENDING, ItemState.GENERATING, ItemState.GENERATED, ItemState.FILTERING).stream().noneMatch(state -> counts.containsKey(state.name()));
    }

    /**
     * Read every completed item of a run, for export.
     *
     * @param runId run to read
     * @return one row per {@link ItemState#FILTERED} item, ordered by topic and index
     */
    public synchronized List<CompletedItem> completedItems(String runId) {
        List<CompletedItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT configuration_id, topic_key, item_index, item_json, provenance_json, decision_json, calls_json
                FROM item WHERE run_id = ? AND state = ? ORDER BY topic_key, item_index""")) {
            statement.setString(1, runId);
            statement.setString(2, ItemState.FILTERED.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new CompletedItem(new ItemKey(runId, rows.getString(1), rows.getString(2), rows.getInt(3)), rows.getString(4), rows.getString(5), rows.getString(6),
                            rows.getString(7)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read completed items of run " + runId, e);
        }
        return items;
    }

    /** A finished item, as stored. */
    public record CompletedItem(ItemKey key, String itemJson, String provenanceJson, String decisionJson, String callsJson) {
    }

    /**
     * @return identifiers of every run in the store, most recent first
     */
    public synchronized List<String> runIds() {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT run_id FROM run ORDER BY created_at DESC");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to list runs", e);
        }
        return ids;
    }

    private Optional<String> queryString(String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private int execute(String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Statement failed: " + sql, e);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        }
        catch (SQLException e) {
            log.warn("Failed to close run store: {}", e.getMessage());
        }
    }
}
