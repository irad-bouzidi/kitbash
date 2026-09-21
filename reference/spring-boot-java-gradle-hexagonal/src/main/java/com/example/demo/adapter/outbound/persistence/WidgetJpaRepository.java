package com.example.demo.adapter.outbound.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data's view of the table. Package-private: only the adapter beside it may use it. */
interface WidgetJpaRepository extends JpaRepository<WidgetRow, Long> {

    boolean existsByName(String name);
}
