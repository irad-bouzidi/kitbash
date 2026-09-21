package com.example.demo.widget.internal;

import com.example.demo.widget.WidgetApi;
import com.example.demo.widget.WidgetSummary;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules live here, not in the controller and not in the entity.
 *
 * <p>It also implements {@link WidgetApi}, which is how the module's published surface and its
 * internal one stay the same code: the alternative — a separate facade that delegates — is two
 * places to change and a standing invitation to let them drift.
 */
@Service
@Transactional(readOnly = true)
class WidgetService implements WidgetApi {

    private final WidgetRepository widgets;

    WidgetService(WidgetRepository widgets) {
        this.widgets = widgets;
    }

    @Override
    public List<WidgetSummary> summaries() {
        return widgets.findAll().stream()
                .map(widget -> new WidgetSummary(widget.id(), widget.name(), widget.quantity()))
                .toList();
    }

    List<Widget> findAll() {
        return widgets.findAll();
    }

    Widget findById(long id) {
        return widgets.findById(id).orElseThrow(() -> new WidgetNotFoundException(id));
    }

    @Transactional
    Widget create(String name, int quantity) {
        if (widgets.existsByName(name)) {
            throw new DuplicateWidgetNameException(name);
        }
        return widgets.save(Widget.of(name, quantity));
    }

    @Transactional
    Widget restock(long id, int amount) {
        Widget widget = findById(id);
        widget.restock(amount);
        return widget;
    }

    @Transactional
    void delete(long id) {
        if (!widgets.existsById(id)) {
            throw new WidgetNotFoundException(id);
        }
        widgets.deleteById(id);
    }
}
