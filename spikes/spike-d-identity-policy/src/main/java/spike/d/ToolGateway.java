package spike.d;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Stand-in tool endpoint. The security decision is made by ToolCallHttpPolicy before this runs. */
@Path("/tools")
public class ToolGateway {

    @POST
    @Path("{tool}/call")
    @Produces(MediaType.TEXT_PLAIN)
    public String call(@PathParam("tool") String tool) {
        return "invoked " + tool;
    }
}
