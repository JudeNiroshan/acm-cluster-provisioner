package org.demo.temenos;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/notify")
public class GreetingResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello RESTEasy";
    }

    Logger logger = Logger.getLogger(GreetingResource.class);

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleAlert(JsonObject json) {
        logger.info("Alert trigger received");
        if (json.getString("status") != null &&
                json.getString("status").equalsIgnoreCase("firing") &&
                !json.getJsonArray("alerts").isEmpty()) {

            logger.info("Alert status = FIRING");
            for (Object obj : json.getJsonArray("alerts")) {
                if (obj instanceof JsonObject) {
                    provisionNewClusterClaim();
                    break;
                }
            }
        }
        return Response.ok().build();
    }

    private void provisionNewClusterClaim() {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            List<GenericKubernetesResource> list1 = client
                    .genericKubernetesResources("hive.openshift.io/v1", "ClusterDeployment")
                    .inNamespace("tcf-spoke01")
                    .list().getItems();
            List<GenericKubernetesResource> list2 = client
                    .genericKubernetesResources("hive.openshift.io/v1", "ClusterDeployment")
                    .inNamespace("tcf-spoke02")
                    .list().getItems();

            if (list1.isEmpty()) {
                logger.error("Could not find tcf-spoke01");
                return;
            }
            if (list2.isEmpty()) {
                logger.error("Could not find tcf-spoke02");
                return;
            }


            GenericKubernetesResource spoke1 = list1.get(0);
            GenericKubernetesResource spoke2 = list2.get(0);

            Map<String, Object> spoke1AdditionalProperties = spoke1.getAdditionalProperties();
            Map<String, Object> spoke2AdditionalProperties = spoke2.getAdditionalProperties();

            if (isPowerStateValid(spoke1AdditionalProperties, spoke2AdditionalProperties)) {

                LinkedHashMap<String, String> spec1 = (LinkedHashMap<String, String>) spoke1AdditionalProperties.get("spec");
                LinkedHashMap<String, String> spec2 = (LinkedHashMap<String, String>) spoke2AdditionalProperties.get("spec");

                String spoke1PowerState = spec1.get("powerState");
                String spoke2PowerState = spec2.get("powerState");
                logger.info("spoke1PowerState: " + spoke1PowerState);
                logger.info("spoke2PowerState: " + spoke2PowerState);

                String labelSpoke01 = getLabel(client, "tcf-spoke01");
                String labelSpoke02 = getLabel(client, "tcf-spoke02");

                if (labelSpoke01 == null || labelSpoke01.isEmpty()) {
                    // spoke01 needs to resume
                    patchSpoke(spoke1, "tcf-spoke01", "Running", false);
                    patchSpoke(spoke2, "tcf-spoke02", "Hibernating", true);
                } else if (labelSpoke02 == null || labelSpoke02.isEmpty()) {
                    // spoke02 needs to resume
                    patchSpoke(spoke2, "tcf-spoke02", "Running", false);
                    patchSpoke(spoke1, "tcf-spoke01", "Hibernating", true);
                }


            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getLabel(KubernetesClient client, String spokeName) {
        Resource<GenericKubernetesResource> resource = client
                .genericKubernetesResources("cluster.open-cluster-management.io/v1", "ManagedCluster")
                .withName(spokeName);
        GenericKubernetesResource customResource = resource.get();
        return customResource.getMetadata().getLabels().get("app");
    }


    private boolean isPowerStateValid(Map<String, Object> spoke1AdditionalProperties, Map<String, Object> spoke2AdditionalProperties) {
        Object spoke1 = spoke1AdditionalProperties.get("spec");
        Object spoke2 = spoke2AdditionalProperties.get("spec");

        if (spoke1 instanceof LinkedHashMap spoke1Spec && spoke2 instanceof LinkedHashMap spoke2Spec) {

            return spoke1Spec.containsKey("powerState") && spoke2Spec.containsKey("powerState");
        }
        return false;
    }

    private void patchSpoke(GenericKubernetesResource spoke, String spokeName, String powerState, boolean isRemoveLabel) {
        Map<String, Object> spokeAdditionalProperties = spoke.getAdditionalProperties();
        LinkedHashMap<String, String> spec = (LinkedHashMap<String, String>) spokeAdditionalProperties.get("spec");

        spec.put("powerState", powerState);
        logger.info("Patching the ClusterDeployment(" + spokeName + ") to " + powerState + " state...");
//        logger.info("Spec section of " + spokeName + " -> " + spokeAdditionalProperties.get("spec"));


        Thread thread = new Thread(() -> {
            KubernetesClient threadLocalClient = new KubernetesClientBuilder().build();
            if (isRemoveLabel) {

                removeLabel(spokeName, threadLocalClient);
                try {
                    // Sleep for 2min seconds
                    logger.info("[" + spokeName + "]:Sleeping for 2mins...");
                    Thread.sleep(120000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                threadLocalClient.resource(spoke).createOrReplace();
                threadLocalClient.close();
                logger.info("[" + spokeName + "]:Done");

            } else {
                threadLocalClient.resource(spoke).createOrReplace();
                try {
                    // Sleep for 2min seconds
                    logger.info("[" + spokeName + "]:Sleeping for 2mins...");
                    Thread.sleep(120000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                addLabel(spokeName, threadLocalClient);
                threadLocalClient.close();
                logger.info("[" + spokeName + "]:Done");
            }

        });
        thread.start();

    }

    private void removeLabel(String managedClusterName, KubernetesClient client) {
        Resource<GenericKubernetesResource> resource = client
                .genericKubernetesResources("cluster.open-cluster-management.io/v1", "ManagedCluster")
                .withName(managedClusterName);
        GenericKubernetesResource customResource = resource.get();
        customResource.getMetadata().getLabels().remove("app");
        client.resource(customResource).createOrReplace();
    }

    private void addLabel(String managedClusterName, KubernetesClient client) {
        Resource<GenericKubernetesResource> resource = client
                .genericKubernetesResources("cluster.open-cluster-management.io/v1", "ManagedCluster")
                .withName(managedClusterName);
        GenericKubernetesResource customResource = resource.get();
        customResource.getMetadata().getLabels().put("app", "demo");
        client.resource(customResource).createOrReplace();
    }

    @GET
    @Path("test")
    @Produces(MediaType.TEXT_PLAIN)
    public String getAlerts() {
        provisionNewClusterClaim();
        return "ok";
//        try (InputStream inputStream = GreetingResource.class.getResourceAsStream("/manifest_yamls/cluster_claim.yaml")) {
//            assert inputStream != null;
//            String result = new BufferedReader(new InputStreamReader(inputStream))
//                    .lines().parallel().collect(Collectors.joining("\n"));
//            return result;
//        } catch (Exception e) {
//            e.printStackTrace();
//            return e.getMessage();
//        }
    }
}