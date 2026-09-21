package com.example.demo.repository;

import com.example.demo.domain.Widget;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WidgetRepository extends JpaRepository<Widget, Long> {

    Optional<Widget> findByName(String name);

    boolean existsByName(String name);
}
