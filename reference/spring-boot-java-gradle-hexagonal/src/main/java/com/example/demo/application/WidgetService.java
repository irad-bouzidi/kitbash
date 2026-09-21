package com.example.demo.application;

import com.example.demo.application.port.inbound.ManageWidgets;
import com.example.demo.application.port.outbound.WidgetRepository;
import com.example.demo.domain.DuplicateWidgetNameException;
import com.example.demo.domain.Widget;
import com.example.demo.domain.WidgetNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use cases. Business rules live here and in the domain, never in an adapter.
 *
 * <p>This class is annotated — {@code @Service}, {@code @Transactional} — and that is deliberate
 * rather than a leak. A transaction boundary is a statement about a use case, so it belongs on the
 * use case. What must stay clean is the <em>domain</em>, and `ArchitectureTest` enforces exactly
 * that line rather than a slogan about framework-free code.
 */
@Service
@Transactional(readOnly = true)
public class WidgetService implements ManageWidgets {

    private final WidgetRepository widgets;

    public WidgetService(WidgetRepository widgets) {
        this.widgets = widgets;
    }

    @Override
    public List<Widget> findAll() {
        return widgets.findAll();
    }

    @Override
    public Widget findById(long id) {
        return widgets.findById(id).orElseThrow(() -> new WidgetNotFoundException(id));
    }

    @Override
    @Transactional
    public Widget create(String name, int quantity) {
        if (widgets.existsByName(name)) {
            throw new DuplicateWidgetNameException(name);
        }
        return widgets.save(Widget.of(name, quantity));
    }

    @Override
    @Transactional
    public Widget restock(long id, int amount) {
        Widget widget = findById(id);
        widget.restock(amount);
        // Explicit, unlike the layered version: a domain object is not a managed entity here, so
        // nothing writes it back for us at commit. That is the trade hexagonal makes.
        return widgets.save(widget);
    }

    @Override
    @Transactional
    public void delete(long id) {
        if (!widgets.existsById(id)) {
            throw new WidgetNotFoundException(id);
        }
        widgets.deleteById(id);
    }
}
