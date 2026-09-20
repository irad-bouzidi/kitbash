package dev.kitbash.api.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Presets, as rows (§10).
 *
 * <p>{@code JdbcClient} rather than an ORM. The table is flat, two of its columns are {@code jsonb}
 * that this layer never looks inside, and there is no entity graph to map — an ORM would add a
 * dialect, a mapping layer and a lazy-loading failure mode in exchange for nothing.
 *
 * <p>The API surface that uses this is {@code kitbash-23}; what is here is what the schema itself
 * has to be able to do, and what a test can hold it to.
 */
public class PresetRepository {

    private final JdbcClient jdbc;

    public PresetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes one revision.
     *
     * <p>Not an upsert. A preset's revisions are the record of how it changed, and an upsert would
     * quietly destroy the one somebody's link points at.
     */
    public Preset insert(Preset preset) {
        jdbc.sql(
                        """
                        insert into preset (id, owner_id, name, description, visibility, selection,
                                            version_policy, pinned_recipes, revision, created_at, updated_at)
                        values (:id, :ownerId, :name, :description, :visibility, cast(:selection as jsonb),
                                :versionPolicy, cast(:pinnedRecipes as jsonb), :revision, :createdAt, :updatedAt)
                        """)
                .param("id", preset.id())
                .param("ownerId", preset.ownerId())
                .param("name", preset.name())
                .param("description", preset.description())
                .param("visibility", preset.visibility().wireName())
                .param("selection", preset.selection())
                .param("versionPolicy", preset.versionPolicy().wireName())
                .param("pinnedRecipes", preset.pinnedRecipes())
                .param("revision", preset.revision())
                .param("createdAt", java.sql.Timestamp.from(preset.createdAt()))
                .param("updatedAt", java.sql.Timestamp.from(preset.updatedAt()))
                .update();
        return preset;
    }

    public Optional<Preset> findById(UUID id) {
        return jdbc.sql("select * from preset where id = :id")
                .param("id", id)
                .query(PresetRepository::map)
                .optional();
    }

    /** One owner's presets, newest revision of each first. */
    public List<Preset> findByOwner(UUID ownerId) {
        return jdbc.sql("select * from preset where owner_id = :ownerId order by name, revision desc")
                .param("ownerId", ownerId)
                .query(PresetRepository::map)
                .list();
    }

    /**
     * Presets somebody else shared.
     *
     * <p>{@code team} and {@code public} both mean "not just mine" today, since §18 has one team.
     * They stay separate columns because {@code public} is the one a role gates, and collapsing
     * them now would mean inventing the distinction again later out of rows that had lost it.
     */
    public List<Preset> findByVisibleToOthers(UUID excludingOwner) {
        return jdbc.sql(
                        """
                        select * from preset
                        where owner_id <> :owner and visibility in ('team', 'public')
                        order by name, revision desc
                        """)
                .param("owner", excludingOwner)
                .query(PresetRepository::map)
                .list();
    }

    /**
     * The revision a new save should take.
     *
     * <p>Computed rather than sequenced, because revisions are per owner and name rather than
     * global. The unique constraint is what makes this safe: two concurrent saves read the same
     * number, and the database refuses the loser rather than letting both write revision 3.
     */
    public int nextRevision(UUID ownerId, String name) {
        return jdbc.sql("select coalesce(max(revision), 0) + 1 from preset where owner_id = :ownerId and name = :name")
                .param("ownerId", ownerId)
                .param("name", name)
                .query(Integer.class)
                .single();
    }

    /** Deletes every revision. A generation made from it keeps its row, with a null preset id. */
    public int deleteByOwnerAndName(UUID ownerId, String name) {
        return jdbc.sql("delete from preset where owner_id = :ownerId and name = :name")
                .param("ownerId", ownerId)
                .param("name", name)
                .update();
    }

    static Preset map(ResultSet row, int rowNumber) throws SQLException {
        return new Preset(
                row.getObject("id", UUID.class),
                row.getObject("owner_id", UUID.class),
                row.getString("name"),
                row.getString("description"),
                Visibility.of(row.getString("visibility")),
                row.getString("selection"),
                VersionPolicy.of(row.getString("version_policy")),
                row.getString("pinned_recipes"),
                row.getInt("revision"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        java.sql.Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
