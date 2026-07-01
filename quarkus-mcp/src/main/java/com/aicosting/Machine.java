package com.aicosting;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Machine catalog, referenced by {@link MachineActivity#machineId}. Exists so the
 * join/aggregate queries in {@link CostingService} exercise a real multi-table
 * join against DuckDB rather than filtering a single table.
 */
@Entity
@Table(name = "machine")
public class Machine extends PanacheEntityBase {

    @Id
    public String id;

    public String name;

    @Column(name = "hourly_rate", nullable = false)
    public double hourlyRate;
}
