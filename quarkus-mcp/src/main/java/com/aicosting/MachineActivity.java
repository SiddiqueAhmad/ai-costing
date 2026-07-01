package com.aicosting;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "machine_activity")
public class MachineActivity extends PanacheEntity {

    @Column(name = "machine_id", nullable = false)
    public String machineId;

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
