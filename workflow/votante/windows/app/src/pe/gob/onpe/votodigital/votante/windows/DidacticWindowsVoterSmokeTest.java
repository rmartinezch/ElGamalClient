package pe.gob.onpe.votodigital.votante.windows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DidacticWindowsVoterSmokeTest {

    private DidacticWindowsVoterSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String stationId = System.getProperty("votante.station.id", "windows");
        String auxsid = firstNonBlank(System.getProperty("votante.station.auxsid", ""),
            System.getProperty("votante.windows.auxsid", ""));
        DidacticWindowsVoterServer.AppConfig config = DidacticWindowsVoterServer.loadConfig(
            stationId,
            firstNonBlank(System.getProperty("votante.station.bindHost", ""), "127.0.0.1"),
            firstNonBlank(System.getProperty("votante.station.publicHost", ""), "127.0.0.1"),
            Integer.parseInt(firstNonBlank(System.getProperty("votante.station.smokePort", ""),
                System.getProperty("votante.windows.smokePort", "8790"))),
            firstNonBlank(System.getProperty("votante.station.serviceBaseUrl", ""),
                System.getProperty("votante.windows.serviceBaseUrl", "")),
                auxsid
        );

        HttpServer server = HttpServer.create(new InetSocketAddress(config.bindHost(), config.port()), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        DidacticWindowsVoterServer.registerContexts(server, config);
        server.start();

        HttpClient httpClient = HttpClient.newBuilder().build();
        try {
            String body = String.join("&",
                    "auxsid=" + HttpUtil.urlEncode(auxsid),
                    "districtCode=02",
                    "presidentialParty=03",
                    "senatorsNationalParty=04",
                    "senatorsNationalPv1=01",
                    "senatorsNationalPv2=02",
                    "senatorsRegionalParty=04",
                    "senatorsRegionalPv1=01",
                    "deputiesParty=03",
                    "deputiesPv1=01",
                    "deputiesPv2=04",
                    "andeanParty=05",
                    "andeanPv1=03",
                    "andeanPv2=04"
            );

            String baseUrl = "http://127.0.0.1:" + config.port();
            String health = send(httpClient, baseUrl + "/api/health", "GET", null);
            String emissionContext = send(httpClient, baseUrl + "/api/service/emission-context", "GET", null);
            String preview = send(httpClient, baseUrl + "/api/ballot/preview", "POST", body);
            String submit = send(httpClient, baseUrl + "/api/ballot/submit", "POST", body);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("health", health);
            payload.put("emissionContext", emissionContext);
            payload.put("preview", preview);
            payload.put("submit", submit);
            System.out.println(JsonUtil.toJson(payload));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static String send(HttpClient httpClient, String url, String method, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }
}
