package dev.demo.observability;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Generates a batch of demo requests in order to exercise the whole
 * telemetry pipeline (traces, metrics and logs) at once.
 */
@WebServlet(name = "LoadGenServlet", urlPatterns = {"/load"})
public class LoadGenServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(LoadGenServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        int count;
        try {
            count = Integer.parseInt(request.getParameter("count"));
        } catch (Exception e) {
            count = 25;
        }
        count = Math.max(1, Math.min(count, 200));

        HttpClient client = HttpClient.newBuilder().build();
        String target =
                "http://localhost:" + request.getLocalPort() + "/demo-observability/demo";

        int ok = 0;
        int err = 0;
        for (int i = 0; i < count; i++) {
            try {
                HttpRequest req =
                        HttpRequest.newBuilder()
                                .uri(URI.create(target))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build();
                HttpResponse<Void> resp =
                        client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    ok++;
                } else {
                    err++;
                }
            } catch (Exception e) {
                err++;
                LOGGER.warning("Load generator request failed: " + e.getMessage());
            }
        }

        LOGGER.info("Load generator produced " + ok + " OK and " + err + " error requests");

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().println(
                "<html><body><h1>Load Generator</h1>"
                        + "<p>Generated " + count + " requests to " + target + "</p>"
                        + "<p>OK: " + ok + ", errors: " + err + "</p>"
                        + "</body></html>");
    }
}
