package com.aicosting;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.List;

/** Full CRUD over machine_activity, used to verify Create/Read/Update/Delete against DuckDB. */
@Path("/activities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ActivityResource {

    @Inject
    CostingService costingService;

    public record ActivityRequest(String machineId, String activityType, LocalDateTime startTime,
            LocalDateTime endTime, String remark, String submittedBy) {
    }

    @GET
    public List<MachineActivity> listAll() {
        return MachineActivity.listAll();
    }

    @GET
    @Path("/{id}")
    public MachineActivity get(@PathParam("id") Long id) {
        return costingService.findActivity(id).orElseThrow(NotFoundException::new);
    }

    @POST
    public Response create(ActivityRequest req) {
        MachineActivity created = costingService.createActivity(
                req.machineId(), req.activityType(), req.startTime(), req.endTime(), req.remark(), req.submittedBy());
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @Path("/{id}")
    public MachineActivity update(@PathParam("id") Long id, ActivityRequest req) {
        return costingService.updateActivity(id, req.activityType(), req.startTime(), req.endTime(), req.remark())
                .orElseThrow(NotFoundException::new);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") Long id) {
        boolean deleted = costingService.deleteActivity(id);
        if (!deleted) {
            throw new NotFoundException();
        }
        return Response.noContent().build();
    }
}
