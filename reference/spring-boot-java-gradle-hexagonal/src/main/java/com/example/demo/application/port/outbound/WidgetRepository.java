package com.example.demo.application.port.outbound;

import com.example.demo.domain.Widget;
import java.util.List;
import java.util.Optional;

/**
 * What the application needs from the outside world, stated without reference to who provides it.
 *
 * <p>A driven port, and the direction of the dependency is the point: this interface is declared
 * *here*, by the code that needs it, rather than in the persistence package that satisfies it. So
 * the adapter depends on the application and never the other way round, which is what the
 * dependency-inversion half of hexagonal actually means.
 *
 * <p>It speaks {@link Widget}, not rows: a port that returned a JPA entity would put the database
 * back in the middle of the application under a different name.
 */
public interface WidgetRepository {

    List<Widget> findAll();

    Optional<Widget> findById(long id);

    boolean existsByName(String name);

    boolean existsById(long id);

    Widget save(Widget widget);

    void deleteById(long id);
}
