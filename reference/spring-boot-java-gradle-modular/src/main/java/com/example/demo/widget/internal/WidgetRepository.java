package com.example.demo.widget.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface WidgetRepository extends JpaRepository<Widget, Long> {

    Optional<Widget> findByName(String name);

    boolean existsByName(String name);
}
