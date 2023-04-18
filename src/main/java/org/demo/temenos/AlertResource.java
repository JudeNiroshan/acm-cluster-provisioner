package org.demo.temenos;

import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("/notify")
public class AlertResource {

    Logger logger = Logger.getLogger(AlertResource.class);

    @POST
    public Response handleAlert(JsonObject json) {
        logger.info(json.toString());
        if (!json.getJsonArray("alerts").isEmpty()) {
            for (Object obj : json.getJsonArray("alerts")) {
                if (obj instanceof JsonObject alert) {
                    logger.info("alert label:" + alert.getString("labels"));
                }
            }
        }
        return Response.ok().build();
    }
}
