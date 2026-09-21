package com.example.demo.service;

import com.example.demo.domain.Widget;
import com.example.demo.repository.WidgetRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules live here, not in the controller and not in the entity. The controller maps
 * HTTP to method calls; this class decides what is allowed.
 */
@Service
@Transactional(readOnly = true)
public class WidgetService {

    private final WidgetRepository widgets;

    public WidgetService(WidgetRepository widgets) {
        this.widgets = widgets;
    }

    public List<Widget> findAll() {
        return widgets.findAll();
    }

    public Widget findById(long id) {
        return widgets.findById(id).orElseThrow(() -> new WidgetNotFoundException(id));
    }

    @Transactional
    public Widget create(String name, int quantity) {
        if (widgets.existsByName(name)) {
            throw new DuplicateWidgetNameException(name);
        }
        return widgets.save(Widget.of(name, quantity));
    }

    @Transactional
    public Widget restock(long id, int amount) {
        Widget widget = findById(id);
        widget.restock(amount);
        return widget;
    }

    @Transactional
    public void delete(long id) {
        if (!widgets.existsById(id)) {
            throw new WidgetNotFoundException(id);
        }
        widgets.deleteById(id);
    }
}
