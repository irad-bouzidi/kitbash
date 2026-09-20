package dev.kitbash.api.store;

import static dev.kitbash.api.store.PresetRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Share links, as rows (§10).
 *
 * <p>The whole table is a token and a selection. There is no owner and no lookup by anything but
 * the token, because the token <i>is</i> the capability — the surface that issues them is
 * {@code kitbash-25}.
 */
public class ShareLinkRepository {

    private final JdbcClient jdbc;

    public ShareLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ShareLink insert(ShareLink link) {
        jdbc.sql(
                        """
                        insert into share_link (token, selection, created_at, expires_at)
                        values (:token, cast(:selection as jsonb), :createdAt, :expiresAt)
                        """)
                .param("token", link.token())
                .param("selection", link.selection())
                .param("createdAt", java.sql.Timestamp.from(link.createdAt()))
                .param("expiresAt", link.expiresAt() == null ? null : java.sql.Timestamp.from(link.expiresAt()))
                .update();
        return link;
    }

    public Optional<ShareLink> find(String token) {
        return jdbc.sql("select * from share_link where token = :token")
                .param("token", token)
                .query(ShareLinkRepository::map)
                .optional();
    }

    static ShareLink map(ResultSet row, int rowNumber) throws SQLException {
        return new ShareLink(
                row.getString("token"),
                row.getString("selection"),
                instant(row, "created_at"),
                instant(row, "expires_at"));
    }
}
