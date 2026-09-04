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

import de.tum.cit.aet.artemis.hyperion.mcq.domain.PoolCell;

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
     * @param database SQLite file; its parent directories are created when absent
     * @throws IllegalStateException if the database cannot be opened or migrated
     */
    public RunStore(Path database) {
        try {
            Path parent = database.toAbsolutePath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            this.connection.setAutoCommit(true);
            migrate();
        }
        catch (SQLException | java.io.IOException e) {
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS attempt (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        item_rowid INTEGER NOT NULL,
                        chosen_option INTEGER NOT NULL,
                        correct INTEGER NOT NULL,
                        answered_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS attempt_by_item ON attempt (item_rowid)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS verdict (
                        item_rowid INTEGER NOT NULL,
                        judge_model TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        accepted INTEGER NOT NULL,
                        decision_json TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (item_rowid, judge_model, scope)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS quiz (
                        quiz_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        configuration_id TEXT NOT NULL,
                        course_key TEXT NOT NULL,
                        request_key TEXT NOT NULL,
                        repetition INTEGER NOT NULL,
                        complete INTEGER NOT NULL,
                        quiz_json TEXT NOT NULL,
                        calls_json TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        UNIQUE (run_id, configuration_id, request_key, repetition)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS document (
                        course_key TEXT NOT NULL,
                        document TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (course_key, document)
                    )""");
        }
        addColumnIfMissing("item", "difficulty", "INTEGER NOT NULL DEFAULT 50");
        addColumnIfMissing("item", "course_key", "TEXT");
        addColumnIfMissing("item", "competency_key", "TEXT");
        addColumnIfMissing("item", "language", "TEXT");
        addColumnIfMissing("item", "question_type", "TEXT");
        addColumnIfMissing("item", "difficulty_band", "TEXT");
        addColumnIfMissing("item", "section_index", "INTEGER");
        addColumnIfMissing("item", "generator_model", "TEXT");
        addColumnIfMissing("verdict", "calls_json", "TEXT");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS pool_lookup ON item (course_key, competency_key, language, question_type, difficulty_band)");
        }
    }

    private void addColumnIfMissing(String table, String column, String type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getInt(1) > 0) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    /** Identifies one unit of work within a run. */
    public record ItemKey(String runId, String configurationId, String topicKey, int itemIndex) {
    }

    /**
     * A unit of work to create, with the difficulty it must be generated at.
     * <p>
     * Difficulty is fixed when the item is enqueued rather than chosen when it is generated, so a run
     * killed and resumed produces the same items.
     *
     * @param difficulty target difficulty from 0 to 100
     */
    public record QueuedItem(ItemKey key, int difficulty) {
    }

    /**
     * A claimed unit of work.
     *
     * @param sectionIndex         which subsection grounds a pool item; 0 for items enqueued without one
     * @param generatedItemJson    the stored item, present when the claim is for filtering
     * @param provenanceJson       the stored provenance, present when the claim is for filtering
     */
    public record Claim(ItemKey key, ItemState state, int difficulty, int sectionIndex, int generationAttempts, int filterAttempts, String generatedItemJson,
            String provenanceJson, String callsJson) {
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
    public synchronized int enqueue(List<QueuedItem> items) {
        int created = 0;
        for (QueuedItem queued : items) {
            ItemKey key = queued.key();
            created += execute("""
                    INSERT OR IGNORE INTO item (run_id, configuration_id, topic_key, item_index, difficulty, state, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", key.runId(), key.configurationId(), key.topicKey(), key.itemIndex(), queued.difficulty(), ItemState.PENDING.name(),
                    Instant.now().toString());
        }
        return created;
    }

    /**
     * Count the items a run already holds for one topic, so newly enqueued indices continue rather than
     * colliding.
     *
     * @param runId           run to inspect
     * @param configurationId configuration within the run
     * @param topicKey        topic to count
     * @return the number of existing rows for that topic
     */
    public synchronized int itemCountForTopic(String runId, String configurationId, String topicKey) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM item WHERE run_id = ? AND configuration_id = ? AND topic_key = ?")) {
            statement.setString(1, runId);
            statement.setString(2, configurationId);
            statement.setString(3, topicKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to count items for topic " + topicKey, e);
        }
    }

    /**
     * One intended pool item.
     *
     * @param sectionIndex which of the competency's subsections grounds this item
     */
    public record PoolItem(ItemKey key, PoolCell cell, int sectionIndex, String generatorModel) {
    }

    /**
     * Enqueue pool items, labelling each row with its cell so retrieval can match on the labels in SQL.
     * <p>
     * Existing rows are left untouched, so enqueueing an unchanged plan on a resumed run creates nothing.
     *
     * @param items the items to create
     * @return the number of rows newly created
     */
    public synchronized int enqueuePool(List<PoolItem> items) {
        int created = 0;
        for (PoolItem item : items) {
            created += execute("""
                    INSERT OR IGNORE INTO item (run_id, configuration_id, topic_key, item_index, state, updated_at, difficulty,
                        course_key, competency_key, language, question_type, difficulty_band, section_index, generator_model)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", item.key().runId(), item.key().configurationId(), item.key().topicKey(), item.key().itemIndex(),
                    ItemState.PENDING.name(), Instant.now().toString(), item.cell().difficulty().promptValue(), item.cell().courseKey(), item.cell().competencyKey(),
                    item.cell().language().code(), item.cell().questionType().value(), item.cell().difficulty().value(), item.sectionIndex(), item.generatorModel());
        }
        return created;
    }

    /**
     * One judge's decision about one item under one scope.
     */
    public record Verdict(long itemRowId, String judgeModel, String scope, boolean accepted, String decisionJson) {
    }

    /**
     * Record a judge's decision about an item, replacing any earlier decision by the same judge under the
     * same scope.
     *
     * @param itemRowId    the judged item
     * @param judgeModel   the model that judged
     * @param scope        the filter scope the decision was made under
     * @param accepted     the decision
     * @param decisionJson the serialised {@code FilterDecision}
     * @param callsJson    the serialised calls that produced the decision, or {@code null} when they are
     *                     already recorded on the item
     */
    public synchronized void recordVerdict(long itemRowId, String judgeModel, String scope, boolean accepted, String decisionJson, String callsJson) {
        execute("INSERT OR REPLACE INTO verdict (item_rowid, judge_model, scope, accepted, decision_json, calls_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", itemRowId,
                judgeModel, scope, accepted ? 1 : 0, decisionJson, callsJson, Instant.now().toString());
    }

    /**
     * Reads one judge's decision about an item.
     *
     * @param itemRowId  the item
     * @param judgeModel the judge
     * @param scope      the filter scope
     * @return the decision, if that judge has made one under that scope
     */
    public synchronized Optional<Verdict> verdict(long itemRowId, String judgeModel, String scope) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT accepted, decision_json FROM verdict WHERE item_rowid = ? AND judge_model = ? AND scope = ?")) {
            statement.setLong(1, itemRowId);
            statement.setString(2, judgeModel);
            statement.setString(3, scope);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Verdict(itemRowId, judgeModel, scope, rows.getInt(1) != 0, rows.getString(2)));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read verdict for item " + itemRowId, e);
        }
    }

    /**
     * Reads the row id of one item, for joining verdicts and attempts to it.
     *
     * @param key the item
     * @return the row id, if the item exists
     */
    public synchronized Optional<Long> rowIdOf(ItemKey key) {
        try (PreparedStatement statement = connection
                .prepareStatement("SELECT rowid FROM item WHERE run_id = ? AND configuration_id = ? AND topic_key = ? AND item_index = ?")) {
            statement.setString(1, key.runId());
            statement.setString(2, key.configurationId());
            statement.setString(3, key.topicKey());
            statement.setInt(4, key.itemIndex());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getLong(1)) : Optional.empty();
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read row id of " + key, e);
        }
    }

    /**
     * A generated pool item one judge has not decided on yet.
     *
     * @param sectionIndex which subsection grounded the item
     */
    public record UnjudgedItem(long id, ItemKey key, String cellKey, int sectionIndex, String itemJson) {
    }

    /**
     * Reads generated pool items the given judge has not yet judged under the given scope.
     *
     * @param judgeModel the judge
     * @param scope      the filter scope
     * @param limit      maximum rows to return
     * @return unjudged items, oldest first
     */
    public synchronized List<UnjudgedItem> itemsMissingVerdict(String judgeModel, String scope, int limit) {
        List<UnjudgedItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT i.rowid, i.run_id, i.configuration_id, i.topic_key, i.item_index, COALESCE(i.section_index, 0), i.item_json
                FROM item i
                WHERE i.item_json IS NOT NULL AND i.course_key IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM verdict v WHERE v.item_rowid = i.rowid AND v.judge_model = ? AND v.scope = ?)
                ORDER BY i.rowid LIMIT ?""")) {
            statement.setString(1, judgeModel);
            statement.setString(2, scope);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new UnjudgedItem(rows.getLong(1), new ItemKey(rows.getString(2), rows.getString(3), rows.getString(4), rows.getInt(5)), rows.getString(4),
                            rows.getInt(6), rows.getString(7)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read items missing a verdict of " + judgeModel, e);
        }
        return items;
    }

    /**
     * Reads the calls persisted with every verdict of the store, for cost reporting.
     *
     * @return serialised call lists, one entry per verdict that carries calls
     */
    public synchronized List<String> verdictCalls() {
        List<String> calls = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT calls_json FROM verdict WHERE calls_json IS NOT NULL");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                calls.add(rows.getString(1));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read verdict calls", e);
        }
        return calls;
    }

    /**
     * One pooled question available for selection.
     */
    public record PoolCandidate(long id, ItemKey key, int sectionIndex, String itemJson, String provenanceJson, String decisionJson) {
    }

    /**
     * Reads the generated questions of one cell that the given generator produced and the given judge
     * accepted.
     * <p>
     * Several generators' pools share one database, so the generator filter is what keeps a configuration's
     * candidates to its own pool. With {@code asOf} given, only items last updated at or before that instant
     * are returned, so a request can be answered from the pool as it stood at that point in time.
     *
     * @param cell           the cell to read
     * @param generatorModel generator whose items qualify
     * @param judgeModel     judge whose acceptance counts
     * @param asOf           ISO-8601 instant, or {@code null} for the current pool
     * @return accepted candidates, oldest first
     */
    public synchronized List<PoolCandidate> poolCandidates(PoolCell cell, String generatorModel, String judgeModel, String asOf) {
        List<PoolCandidate> candidates = new ArrayList<>();
        String sql = """
                SELECT i.rowid, i.run_id, i.configuration_id, i.topic_key, i.item_index, i.section_index, i.item_json, i.provenance_json, v.decision_json
                FROM item i JOIN verdict v ON v.item_rowid = i.rowid
                WHERE i.course_key = ? AND i.competency_key = ? AND i.language = ? AND i.question_type = ? AND i.difficulty_band = ?
                  AND i.generator_model = ? AND i.item_json IS NOT NULL AND v.judge_model = ? AND v.scope = ? AND v.accepted = 1
                """ + (asOf == null ? "" : " AND i.updated_at <= ?") + " ORDER BY i.rowid";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cell.courseKey());
            statement.setString(2, cell.competencyKey());
            statement.setString(3, cell.language().code());
            statement.setString(4, cell.questionType().value());
            statement.setString(5, cell.difficulty().value());
            statement.setString(6, generatorModel);
            statement.setString(7, judgeModel);
            statement.setString(8, "GENERAL");
            if (asOf != null) {
                statement.setString(9, asOf);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    candidates.add(new PoolCandidate(rows.getLong(1), new ItemKey(rows.getString(2), rows.getString(3), rows.getString(4), rows.getInt(5)), rows.getInt(6),
                            rows.getString(7), rows.getString(8), rows.getString(9)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read pool candidates for cell " + cell.key(), e);
        }
        return candidates;
    }

    /**
     * One assembled quiz.
     */
    public record StoredQuiz(String quizId, String runId, String configurationId, String courseKey, String requestKey, int repetition, boolean complete, String quizJson,
            String callsJson) {
    }

    /**
     * Record an assembled quiz, replacing an earlier one of the same identity.
     *
     * @param quiz the quiz to store
     */
    public synchronized void saveQuiz(StoredQuiz quiz) {
        execute("""
                INSERT OR REPLACE INTO quiz (quiz_id, run_id, configuration_id, course_key, request_key, repetition, complete, quiz_json, calls_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", quiz.quizId(), quiz.runId(), quiz.configurationId(), quiz.courseKey(), quiz.requestKey(), quiz.repetition(),
                quiz.complete() ? 1 : 0, quiz.quizJson(), quiz.callsJson(), Instant.now().toString());
    }

    /**
     * Counts the quizzes stored under a run.
     *
     * @param runId run to count for
     * @return the number of stored quizzes
     */
    public synchronized int quizCount(String runId) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM quiz WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to count quizzes of run " + runId, e);
        }
    }

    /**
     * Whether a quiz for this cell of the sweep already exists, so a resumed sweep skips it.
     *
     * @return {@code true} when the quiz is already stored
     */
    public synchronized boolean quizExists(String runId, String configurationId, String requestKey, int repetition) {
        return queryString("SELECT quiz_id FROM quiz WHERE run_id = ? AND configuration_id = ? AND request_key = ? AND repetition = ?", runId, configurationId, requestKey,
                repetition).isPresent();
    }

    /**
     * Reads the run ids that hold stored quizzes, most recent first.
     *
     * @return distinct sweep run ids
     */
    public synchronized List<String> quizRunIds() {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT run_id, MAX(created_at) FROM quiz GROUP BY run_id ORDER BY MAX(created_at) DESC");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to list quiz runs", e);
        }
        return ids;
    }

    /**
     * Reads one stored quiz.
     *
     * @param quizId the quiz
     * @return the quiz, if stored
     */
    public synchronized Optional<StoredQuiz> quiz(String quizId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT quiz_id, run_id, configuration_id, course_key, request_key, repetition, complete, quiz_json, calls_json
                FROM quiz WHERE quiz_id = ?""")) {
            statement.setString(1, quizId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredQuiz(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getInt(6),
                        rows.getInt(7) != 0, rows.getString(8), rows.getString(9)));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read quiz " + quizId, e);
        }
    }

    /**
     * Reads every stored quiz of a run.
     *
     * @param runId the run
     * @return quizzes ordered by configuration, request and repetition
     */
    public synchronized List<StoredQuiz> quizzes(String runId) {
        List<StoredQuiz> quizzes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT quiz_id, run_id, configuration_id, course_key, request_key, repetition, complete, quiz_json, calls_json
                FROM quiz WHERE run_id = ? ORDER BY configuration_id, request_key, repetition""")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    quizzes.add(new StoredQuiz(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getInt(6), rows.getInt(7) != 0,
                            rows.getString(8), rows.getString(9)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read quizzes of run " + runId, e);
        }
        return quizzes;
    }

    /**
     * Reads the recorded content hash of every document of a course.
     *
     * @param courseKey the course
     * @return document path to content hash
     */
    public synchronized Map<String, String> documentHashes(String courseKey) {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT document, content_hash FROM document WHERE course_key = ? ORDER BY document")) {
            statement.setString(1, courseKey);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    hashes.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read document hashes for course " + courseKey, e);
        }
        return hashes;
    }

    /**
     * Record a document's content hash, replacing any earlier one, so the next pool build can tell changed
     * and new documents from unchanged ones.
     *
     * @param courseKey   the course
     * @param document    corpus-relative document path
     * @param contentHash hash of the document's bytes
     */
    public synchronized void recordDocumentHash(String courseKey, String document, String contentHash) {
        execute("INSERT OR REPLACE INTO document (course_key, document, content_hash, updated_at) VALUES (?, ?, ?, ?)", courseKey, document, contentHash,
                Instant.now().toString());
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
                SELECT configuration_id, topic_key, item_index, difficulty, COALESCE(section_index, 0), generation_attempts, filter_attempts, item_json, provenance_json,
                    calls_json
                FROM item WHERE run_id = ? AND state = ? ORDER BY updated_at DESC LIMIT 1""")) {
            statement.setString(1, runId);
            statement.setString(2, to.name());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                ItemKey key = new ItemKey(runId, rows.getString(1), rows.getString(2), rows.getInt(3));
                return Optional.of(new Claim(key, to, rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getString(8), rows.getString(9), rows.getString(10)));
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
    /**
     * Replace an item's stored decision without touching its state or attempt counts.
     * <p>
     * For recomputing decisions from verdicts already judged, which needs no model call. Deliberately not
     * {@code recordFiltered}, which would count another filter attempt that never happened.
     *
     * @param key      the item
     * @param decision serialised decision
     */
    public synchronized void replaceDecision(ItemKey key, String decision) {
        update(key, "decision_json = ?", List.of(decision));
    }

    /**
     * Read the manifest a run was registered with.
     *
     * @param runId run to inspect
     * @return the stored manifest, or empty when the run is unknown
     */
    public synchronized Optional<String> manifestOf(String runId) {
        return queryString("SELECT manifest FROM run WHERE run_id = ?", runId);
    }

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
     * An item that exhausted its attempts.
     *
     * @param state   the terminal state reached
     * @param failure the category of the final failure
     */
    public record FailedItem(ItemKey key, ItemState state, String failure, int generationAttempts, int filterAttempts, String callsJson) {
    }

    /**
     * Read items that failed permanently. These never appear in the exported run log, because they have no
     * question to export, so this is the only record that they were attempted at all.
     *
     * @param runId run to read
     * @return failed items ordered by topic and index
     */
    public synchronized List<FailedItem> failedItems(String runId) {
        List<FailedItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT configuration_id, topic_key, item_index, state, failure, generation_attempts, filter_attempts, calls_json
                FROM item WHERE run_id = ? AND state IN (?, ?) ORDER BY topic_key, item_index""")) {
            statement.setString(1, runId);
            statement.setString(2, ItemState.FAILED_GENERATION.name());
            statement.setString(3, ItemState.FAILED_FILTER.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new FailedItem(new ItemKey(runId, rows.getString(1), rows.getString(2), rows.getInt(3)), ItemState.valueOf(rows.getString(4)), rows.getString(5),
                            rows.getInt(6), rows.getInt(7), rows.getString(8)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read failed items of run " + runId, e);
        }
        return items;
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

    /**
     * A row of the item list, flat enough to render without deserialising the stored question.
     *
     * @param id             stable identifier for linking, the underlying row id
     * @param accepted       {@code null} until the item has been judged
     * @param aggregateScore {@code null} until the item has been judged
     */
    public record ItemSummary(long id, String runId, String topicKey, int itemIndex, ItemState state, String title, Boolean accepted, Double aggregateScore, int attempts) {
    }

    /** One stored item with everything needed for a detail view. */
    public record ItemDetail(long id, ItemKey key, ItemState state, String itemJson, String provenanceJson, String decisionJson, String callsJson) {
    }

    /** One recorded answer. */
    public record Attempt(long id, long itemRowId, int chosenOption, boolean correct, String answeredAt) {
    }

    /**
     * How many answers have been recorded across every item, and how many of them were correct.
     *
     * @param recorded total attempts
     * @param correct  attempts whose chosen option was the correct one
     */
    public record AttemptTotals(int recorded, int correct) {
    }

    /**
     * List items, most recently updated first.
     *
     * @param runId    restrict to one run, or {@code null} for all runs
     * @param topicKey restrict to one topic, or {@code null} for all topics
     * @param accepted restrict to accepted or rejected items, or {@code null} for both
     * @param limit    maximum rows to return
     * @return matching item summaries
     */
    public synchronized List<ItemSummary> browse(String runId, String topicKey, Boolean accepted, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.rowid, i.run_id, i.topic_key, i.item_index, i.state,
                       json_extract(i.item_json, '$.title'),
                       json_extract(i.decision_json, '$.accepted'),
                       json_extract(i.decision_json, '$.aggregateScore'),
                       (SELECT COUNT(*) FROM attempt a WHERE a.item_rowid = i.rowid)
                FROM item i WHERE i.item_json IS NOT NULL""");
        List<Object> parameters = new ArrayList<>();
        if (runId != null) {
            sql.append(" AND i.run_id = ?");
            parameters.add(runId);
        }
        if (topicKey != null) {
            sql.append(" AND i.topic_key = ?");
            parameters.add(topicKey);
        }
        if (accepted != null) {
            sql.append(" AND json_extract(i.decision_json, '$.accepted') = ?");
            parameters.add(accepted ? 1 : 0);
        }
        sql.append(" ORDER BY i.updated_at DESC LIMIT ?");
        parameters.add(limit);

        List<ItemSummary> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters.toArray());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Object acceptedValue = rows.getObject(7);
                    Object scoreValue = rows.getObject(8);
                    items.add(new ItemSummary(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getInt(4), ItemState.valueOf(rows.getString(5)), rows.getString(6),
                            acceptedValue == null ? null : ((Number) acceptedValue).intValue() != 0, scoreValue == null ? null : ((Number) scoreValue).doubleValue(),
                            rows.getInt(9)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to browse items", e);
        }
        return items;
    }

    /**
     * Reads the item with the given row id.
     *
     * @param id row id of the item
     * @return the item, if it exists and has been generated
     */
    public synchronized Optional<ItemDetail> item(long id) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id, configuration_id, topic_key, item_index, state, item_json, provenance_json, decision_json, calls_json
                FROM item WHERE rowid = ?""")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                ItemKey key = new ItemKey(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4));
                return Optional.of(new ItemDetail(id, key, ItemState.valueOf(rows.getString(5)), rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9)));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read item " + id, e);
        }
    }

    /**
     * Record an answer to an item.
     *
     * @param itemRowId    the item answered
     * @param chosenOption zero-based index of the option chosen
     * @param correct      whether that option was the correct one
     */
    public synchronized void recordAttempt(long itemRowId, int chosenOption, boolean correct) {
        execute("INSERT INTO attempt (item_rowid, chosen_option, correct, answered_at) VALUES (?, ?, ?, ?)", itemRowId, chosenOption, correct ? 1 : 0, Instant.now().toString());
    }

    /**
     * Reads every answer recorded against one item.
     *
     * @param itemRowId the item
     * @return attempts against that item, oldest first
     */
    public synchronized List<Attempt> attempts(long itemRowId) {
        List<Attempt> attempts = new ArrayList<>();
        try (PreparedStatement statement = connection
                .prepareStatement("SELECT id, item_rowid, chosen_option, correct, answered_at FROM attempt WHERE item_rowid = ? ORDER BY id")) {
            statement.setLong(1, itemRowId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    attempts.add(new Attempt(rows.getLong(1), rows.getLong(2), rows.getInt(3), rows.getInt(4) != 0, rows.getString(5)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read attempts for item " + itemRowId, e);
        }
        return attempts;
    }

    /**
     * Summarises every recorded answer.
     *
     * @return the totals, both zero when nothing has been answered
     */
    public synchronized AttemptTotals attemptTotals() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*), COALESCE(SUM(correct), 0) FROM attempt");
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? new AttemptTotals(rows.getInt(1), rows.getInt(2)) : new AttemptTotals(0, 0);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to summarise attempts", e);
        }
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
