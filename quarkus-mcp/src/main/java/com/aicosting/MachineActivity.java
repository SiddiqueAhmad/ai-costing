package com.aicosting;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "machine_activity")
public class MachineActivity extends PanacheEntity {

    @Column(name = "machine_id", nullable = false)
    public String machineId;

    // Read-only join to the Machine catalog row sharing the same machine_id.
    // machineId above stays the owning/writable column; this mapping exists
    // purely so JPQL/Panache queries can traverse a real foreign-key join.
    //
    // foreignKey = NO_CONSTRAINT: DuckDB's ALTER TABLE doesn't support ADD
    // CONSTRAINT ... FOREIGN KEY (only inline FKs at CREATE TABLE time), so
    // Hibernate's default post-hoc ALTER TABLE for this @ManyToOne fails
    // schema generation outright. The join itself (JPQL/SQL) is unaffected;
    // only DDL-time constraint generation needs to be skipped.
    //
    // @JsonIgnore: lazy and only populated by join-fetch queries; serializing
    // it from a plain findById() (REST CRUD) would throw LazyInitializationException
    // once outside the originating Hibernate session.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @JsonIgnore
    public Machine machine;

    @Column(name = "activity_type", nullable = false)
    public String activityType;

    @Column(name = "start_time", nullable = false)
    public LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    public LocalDateTime endTime;

    public String remark;

    @Column(name = "submitted_by")
    public String submittedBy;

    public double durationHours() {
        return Duration.between(startTime, endTime).toSeconds() / 3600.0;
    }
}
