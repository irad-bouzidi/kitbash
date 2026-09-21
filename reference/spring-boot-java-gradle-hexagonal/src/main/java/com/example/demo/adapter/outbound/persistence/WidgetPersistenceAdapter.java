package com.example.demo.adapter.outbound.persistence;

import com.example.demo.application.port.outbound.WidgetRepository;
import com.example.demo.domain.Widget;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The driven adapter: it implements a port the application declared, and it is the only class in
 * the project that knows both a {@link Widget} and a {@link WidgetRow}.
 *
 * <p>The mapping is hand-written and that is the honest cost of the architecture. Two methods, no
 * mapping framework, and a translation that anyone can read — which is a better trade at this size
 * than a library that has to be configured before a reader can tell what a field becomes.
 */
@Component
class WidgetPersistenceAdapter implements WidgetRepository {

    private final WidgetJpaRepository rows;

    WidgetPersistenceAdapter(WidgetJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public List<Widget> findAll() {
        return rows.findAll().stream().map(WidgetPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Optional<Widget> findById(long id) {
        return rows.findById(id).map(WidgetPersistenceAdapter::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return rows.existsByName(name);
    }

    @Override
    public boolean existsById(long id) {
        return rows.existsById(id);
    }

    @Override
    public Widget save(Widget widget) {
        return toDomain(rows.save(new WidgetRow(widget.id(), widget.name(), widget.quantity(), widget.createdAt())));
    }

    @Override
    public void deleteById(long id) {
        rows.deleteById(id);
    }

    private static Widget toDomain(WidgetRow row) {
        return Widget.existing(row.id(), row.name(), row.quantity(), row.createdAt());
    }
}
