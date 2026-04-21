package com.sailpoint.ticketManagement.rest;

import sailpoint.api.SailPointContext;
import sailpoint.object.Filter;
import sailpoint.object.Identity;
import sailpoint.rest.plugin.RequiredRight;
import sailpoint.object.QueryOptions;
import sailpoint.rest.plugin.BasePluginResource;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@RequiredRight("TicketCreationView")
@Path("identitySearch")
@Produces(MediaType.APPLICATION_JSON)
public class IdentitySearchResource extends BasePluginResource {

    @Override
    public String getPluginName() {
        return "TicketCreation";
    }

    @GET
    @Path("search")
    public List<Map<String, String>> search(@QueryParam("q") String q,
                                            @QueryParam("max") Integer max) {
        List<Map<String, String>> results = new ArrayList<Map<String, String>>();

        try {
            String query = q == null ? "" : q.trim();
            if (query.length() < 1) {
                return results;
            }

            int limit = (max == null || max.intValue() <= 0) ? 10 : Math.min(max.intValue(), 20);

            SailPointContext context = getContext();
            QueryOptions qo = new QueryOptions();
            qo.setResultLimit(limit);

            List<Filter> orFilters = new ArrayList<Filter>();
            orFilters.add(Filter.like("name", query, Filter.MatchMode.START));
            orFilters.add(Filter.like("displayName", query, Filter.MatchMode.ANYWHERE));
            orFilters.add(Filter.like("email", query, Filter.MatchMode.START));

            qo.addFilter(Filter.eq("workgroup", false));
            qo.addFilter(Filter.or(orFilters));
            qo.addOrdering("displayName", true);

            List<Identity> identities = context.getObjects(Identity.class, qo);
            if (identities != null) {
                for (Identity identity : identities) {
                    Map<String, String> row = new HashMap<String, String>();
                    row.put("id", safe(identity.getId()));
                    row.put("username", safe(identity.getName()));
                    row.put("displayName", safe(identity.getDisplayName()));
                    row.put("email", safe(identity.getEmail()));
                    results.add(row);
                }
            }

            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to search identities", e);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}