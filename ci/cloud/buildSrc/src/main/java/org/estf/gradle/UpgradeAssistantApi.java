/**
 *
 * Kibana upgrade assistant via API class
 *
 * @author  Liza Mae Dayoub
 *
 */

package org.estf.gradle;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;

import java.io.IOException;
import java.util.Iterator;

public class UpgradeAssistantApi extends DefaultTask {

    @Input
    String esBaseUrl;

    @Input
    String kbnBaseUrl;

    @Input
    String username;

    @Input
    String password;

    @Input
    String version;

    @Input
    String upgradeVersion;

    int majorVersion;
    int majorUpgradeVersion;

    final int REINDEX_TIMEOUT = 60000;
    final int CHECK_INTERVAL = 5000;

    @TaskAction
    public void run() throws IOException, InterruptedException {
        RestApi api = new RestApi(username, password, version, upgradeVersion);
        majorVersion = api.setMajorVersion();
        majorUpgradeVersion = api.setMajorUpgradeVersion();
        if (majorVersion != majorUpgradeVersion) {
            System.out.println("Performing major upgrade...");
            if (majorVersion == 5) {
                setKibanaIndexReadOnly(api);
                createKibana6Index(api);
                reindexKibana6Index(api);
                aliasKibana6Index(api);
                runMigrationAssistant5(api);
            } else if (majorVersion == 6) {
                runMigrationAssistant6(api);
            } else {
                throw new IOException("Major upgrade from 7.x not yet supported");
                // Ref: https://github.com/elastic/kibana/issues/76837
                //runUpgradeAssistantStatus(api);
            }
        } else {
            System.out.println("Performing minor upgrade...");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void runMigrationAssistant5(RestApi api) throws IOException {
        HttpResponse response;
        HttpEntity entity;
        String content;
        String path = "/_migration/assistance";
        String post_path = "/migration/upgrade/<index>";
        if (majorVersion < 7) {
            path = "/_xpack/migration/assistance";
            post_path = "/_xpack/migration/upgrade/<index>";
        }
        response = api.get(esBaseUrl + path);
        entity = response.getEntity();
        content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        System.out.println(json.toString());
        JSONObject indices = json.getJSONObject("indices");
        Iterator keys = indices.keys();
        while (keys.hasNext()) {
            String key = keys.next().toString();
            JSONObject actionreq = indices.getJSONObject(key);
            String action = actionreq.get("action_required").toString();
            if (action.equals("upgrade")) {
                System.out.println(key);
                response = api.post(esBaseUrl + post_path.replace("<index>", key),
                                    "{\"wait_for_completion\": true}", false);
                entity = response.getEntity();
                content = EntityUtils.toString(entity);
                JSONObject json1 = new JSONObject(content);
                JSONArray failures = json1.getJSONArray("failures");
                if (failures.length() > 0) {
                    throw new IOException("Reindex Kibana index failed!");
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void runMigrationAssistant6(RestApi api) throws IOException, InterruptedException {
        HttpResponse response;
        HttpEntity entity;
        String content;
        String path = "/_xpack/migration/assistance";
        response = api.get(esBaseUrl + path);
        entity = response.getEntity();
        content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        System.out.println(json.toString());
        JSONObject indices = json.getJSONObject("indices");
        Iterator keys = indices.keys();
        while (keys.hasNext()) {
            String key = keys.next().toString();
            JSONObject actionreq = indices.getJSONObject(key);
            String action = actionreq.get("action_required").toString();
            if (action.equals("reindex")) {
                runUpgradeAssistantReindex(api, key);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void runUpgradeAssistantStatus(RestApi api) throws IOException, InterruptedException {
        HttpResponse response;
        HttpEntity entity;
        String content;
        String path = "/api/upgrade_assistant/status";
        response = api.get(kbnBaseUrl + path);
        entity = response.getEntity();
        content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        System.out.println("\n" + json.toString() + "\n");
        JSONArray indices = json.getJSONArray("indices");
        for (int i = 0; i < indices.length(); i++) {
            JSONObject indicesElem = indices.getJSONObject(i);
            Boolean reindex = indicesElem.getBoolean("reindex");
            String index = indicesElem.getString("index");
            if (reindex) {
                runUpgradeAssistantReindex(api, index);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public String runUpgradeAssistantReindex(RestApi api, String key) throws IOException, InterruptedException {
        String path = "/api/upgrade_assistant/reindex/<index>";
        HttpResponse response = api.post(kbnBaseUrl + path.replace("<index>", key),
                                         "", true);
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        String newIndexName = json.getString("newIndexName");
        checkUpgradeAssistantReindex(api, key);
        return newIndexName;
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void checkUpgradeAssistantReindex(RestApi api, String key) throws IOException, InterruptedException {
        String path = "/api/upgrade_assistant/reindex/<index>";
        long finish = System.currentTimeMillis() + REINDEX_TIMEOUT;
        HttpResponse response = api.get(kbnBaseUrl + path.replace("<index>", key));
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        int status = json.getJSONObject("reindexOp").getInt("status");
        while (status == 0 && System.currentTimeMillis() < finish) {
            response = api.get(kbnBaseUrl + path.replace("<index>", key));
            entity = response.getEntity();
            content = EntityUtils.toString(entity);
            json = new JSONObject(content);
            status = json.getJSONObject("reindexOp").getInt("status");
            Thread.sleep(CHECK_INTERVAL);
        }
        if (status != 1) {
            throw new IOException("Reindex failed! Status is " + String.valueOf(status));
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void setKibanaIndexReadOnly(RestApi api) throws IOException {
        String path = "/.kibana/_settings";
        String jsonstr = "{\"index.blocks.write\":true}";
        HttpResponse response = api.put(esBaseUrl + path, jsonstr, false);
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        Boolean acknowledged = json.getBoolean("acknowledged");
        if (! acknowledged) {
            throw new IOException("Settings block writes failed!");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void createKibana6Index(RestApi api) throws IOException {
        String path = "/.kibana-6";
        String file = "product/json/mappings6.json";
        String jsonstr = new String(Files.readAllBytes(Paths.get(file)));
        HttpResponse response = api.put(esBaseUrl + path, jsonstr, false);
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        Boolean acknowledged = json.getBoolean("acknowledged");
        Boolean shards_acknowledged = json.getBoolean("shards_acknowledged");
        if (! acknowledged) {
            throw new IOException("Create Kibana index failed!");
        }
        if (! shards_acknowledged) {
            throw new IOException("Create Kibana index shards failed!");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void reindexKibana6Index(RestApi api) throws IOException {
        String path = "/_reindex?wait_for_completion=true";
        String file = "product/json/reindex6.json";
        String jsonstr = new String(Files.readAllBytes(Paths.get(file)));
        HttpResponse response = api.post(esBaseUrl + path, jsonstr, false);
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        JSONArray failures = json.getJSONArray("failures");
        if (failures.length() > 0) {
            throw new IOException("Reindex Kibana index failed!");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    public void aliasKibana6Index(RestApi api) throws IOException {
        String path = "/_aliases";
        String file = "product/json/alias6.json";
        String jsonstr = new String(Files.readAllBytes(Paths.get(file)));
        HttpResponse response = api.post(esBaseUrl + path, jsonstr, false);
        HttpEntity entity = response.getEntity();
        String content = EntityUtils.toString(entity);
        JSONObject json = new JSONObject(content);
        Boolean acknowledged = json.getBoolean("acknowledged");
        if (! acknowledged) {
            throw new IOException("Settings block writes failed!");
        }
    }
}
