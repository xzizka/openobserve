package dev.demo.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.logging.Logger;

/**
 * Demo servlet that reports logs, traces and metrics to OpenObserve.
 *
 * The OpenTelemetry Java agent is attached at startup (set via CATALINA_OPTS).
 * It provides the SDK, exporters and automatic instrumentation. This servlet
 * additionally creates explicit spans, a counter metric and log records so all
 * three signals are produced per request.
 */
@WebServlet(name = "DemoServlet", urlPatterns = {"/", "/demo"})
public class DemoServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(DemoServlet.class.getName());

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Tracer tracer =
            GlobalOpenTelemetry.get().getTracer("demo-observability");
    private final LongCounter requestCounter;

    public DemoServlet() {
        Meter meter =
                GlobalOpenTelemetry.get().getMeter("demo-observability");
        this.requestCounter =
                meter.counterBuilder("demo.requests.total")
                        .setDescription("Total number of demo requests processed")
                        .setUnit("{request}")
                        .build();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // Increment a custom metric counter.
        requestCounter.add(1);

        // Work duration, shared between span processing and rendering.
        int workMs = 20 + RANDOM.nextInt(180);

        // Create an explicit child span inside the auto-instrumented HTTP span.
        Span processingSpan =
                tracer.spanBuilder("demo/process").setAttribute("demo.work", true).startSpan();
        try (Scope unused = processingSpan.makeCurrent()) {

            // Simulate some internal work with variable latency.
            try {
                Thread.sleep(workMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            processingSpan.setAttribute("demo.latency_ms", workMs);

            // Emit a log record. The agent bridges java.util.logging, attaching
            // trace_id/span_id so this log correlates with the current span.
            if (workMs > 150) {
                LOGGER.warning("High latency detected for demo request: " + workMs + "ms");
            } else {
                LOGGER.info("Processed demo request in " + workMs + "ms");
            }

            if (workMs % 15 == 0) {
                throw new IllegalStateException("Random simulated processing error");
            }
        } finally {
            processingSpan.end();
        }

        render(response.getWriter(), workMs);
    }

    private void render(PrintWriter out, int workMs) {
        out.println("<html><body>");
        out.println("<h1>Demo Observability</h1>");
        out.println("<p>Hello from demo-observability.</p>");
        out.println("<p>This request's latency: " + workMs + "ms</p>");
        out.println("<p>Service: demo-observability</p>");
        out.println("</body></html>");
    }
}
