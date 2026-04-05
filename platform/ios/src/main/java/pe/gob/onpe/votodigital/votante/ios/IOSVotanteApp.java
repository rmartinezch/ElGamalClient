package pe.gob.onpe.votodigital.votante.ios;

import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IOSVotanteApp extends Application {

    private static final Logger LOG = Logger.getLogger(IOSVotanteApp.class.getName());
    private static final String[] WEB_RESOURCES = {
        "index.html", "app.js", "styles.css", "catalogo-opciones.json"
    };
    private IOSVoterBridge voterBridge;

    @Override
    public void start(Stage primaryStage) {
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);

        IOSCipherRunner cipherRunner = new IOSCipherRunner(appDataDir());
        voterBridge = new IOSVoterBridge(cipherRunner);
        voterBridge.setWebEngine(webEngine);

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webEngine.executeScript("window");
                window.setMember("iOSBridge", voterBridge);
                webEngine.executeScript(
                    "window.onerror = function(msg, src, line, col, err) {"
                    + "  console.log('[JS ERROR] ' + msg + ' at ' + src + ':' + line);"
                    + "  return false;"
                    + "};"
                    + "console.log = console.log || function(){};"
                    + "var _origLog = console.log;"
                    + "console.log = function() {"
                    + "  _origLog.apply(console, arguments);"
                    + "  if (window.iOSBridge && window.iOSBridge.logFromJs) {"
                    + "    window.iOSBridge.logFromJs(Array.prototype.join.call(arguments, ' '));"
                    + "  }"
                    + "};"
                );
                LOG.info("iOSBridge inyectado en el WebView.");
            }
        });

        webEngine.setOnAlert(event -> LOG.info("JS Alert: " + event.getData()));

        File webDir = extractWebResources();
        if (webDir != null) {
            File indexFile = new File(webDir, "index.html");
            String fileUrl = indexFile.toURI().toString();
            webEngine.load(fileUrl);
            LOG.info("Cargando UI desde: " + fileUrl);
        } else {
            LOG.severe("No se pudieron extraer los recursos web.");
            webEngine.loadContent("<h1>Error: no se pudieron extraer recursos web</h1>", "text/html");
        }

        StackPane root = new StackPane(webView);
        Scene scene = new Scene(root, 430, 932);
        primaryStage.setTitle("Votante iOS");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private File extractWebResources() {
        try {
            Path webDir = Files.createTempDirectory("votante-web");
            for (String name : WEB_RESOURCES) {
                try (InputStream in = getClass().getResourceAsStream("/votante/" + name)) {
                    if (in == null) {
                        LOG.warning("Recurso no encontrado: /votante/" + name);
                        continue;
                    }
                    Files.copy(in, webDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return webDir.toFile();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Error extrayendo recursos web", e);
            return null;
        }
    }

    private File appDataDir() {
        String userHome = System.getProperty("user.home", ".");
        File dataDir = new File(userHome, "Documents/VotanteIOS");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        return dataDir;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
