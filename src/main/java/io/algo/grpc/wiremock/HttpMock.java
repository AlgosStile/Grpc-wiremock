package io.algo.grpc.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.standalone.CommandLineOptions;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import io.algo.grpc.wiremock.configurer.WiremockConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static io.algo.grpc.wiremock.HeaderPropagationInterceptor.HEADERS;

@Component
public class HttpMock {
    private static final Logger LOG = LoggerFactory.getLogger(HttpMock.class);
    private static final String PREFIX = "wiremock_";
    private final WiremockConfigurer configurer;
    private final ProtoJsonConverter converter;
    private WireMockServer server;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public HttpMock(WiremockConfigurer configurer, ProtoJsonConverter converter) {
        this.configurer = configurer;
        this.converter = converter;
    }

    public void start() {
        String[] args = configurer.configure(envOptions());
        LOG.info("Starting WireMock server with options:\n{}", String.join("\n", args));
        CommandLineOptions options = new CommandLineOptions(args);
        server = new WireMockServer(options);
        server.start();
        LOG.info("WireMock server is started:\n{}", setActualPort(options));
    }

    private CommandLineOptions setActualPort(CommandLineOptions options) {
        if (!options.getHttpDisabled()) {
            options.setActualHttpPort(server.port());
        }
        if (options.httpsSettings().enabled()) {
            options.setActualHttpsPort(server.httpsPort());
        }
        return options;
    }

    private String[] envOptions() {
        return System.getenv().entrySet().stream()
            .filter(it -> it.getKey().toLowerCase().startsWith(PREFIX))
            .map(this::toWiremockOption)
            .toArray(String[]::new);
    }

    private String toWiremockOption(Map.Entry<String, String> it) {
        return "--" + it.getKey().toLowerCase().substring(PREFIX.length()) + (nullOrEmpty(it.getValue()) ? "" : "=" + it.getValue());
    }

    private boolean nullOrEmpty(String value) {
        return value == null || value.equals("");
    }

    @PreDestroy
    public void destroy() {
        server.stop();
    }

    public Response request(String path, Object message, Map<String, String> headers) throws IOException, InterruptedException {
        headers.putAll(HEADERS.get());
        LOG.info("Grpc request {}:\nHeaders: {}\nMessage:\n{}", path, headers, message);
        return new Response(
            httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/" + path))
                    .POST(asJson(message))
                    .headers(headers.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue())).toArray(String[]::new))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream()
            ),
            converter
        );
    }

    public Response request(String path, Object message) throws IOException, InterruptedException {
        return request(path, message, new HashMap<>());
    }

    public static final class Response {
        private final HttpResponse<InputStream> httpResponse;
        private final ProtoJsonConverter converter;

        public Response(HttpResponse<InputStream> httpResponse, ProtoJsonConverter converter) {
            this.httpResponse = httpResponse;
            this.converter = converter;
        }

        public Message getMessage(Class<?> aClass) {
            if (httpResponse.statusCode() == 200) {
                return converter.fromJson(getBody(), aClass);
            }
            throw new BadHttpResponseException(httpResponse.statusCode(), getBody());
        }

        public int streamSize() {
            return httpResponse.headers().firstValue("streamSize").map(Integer::valueOf).orElse(1);
        }

        private String getBody() {
            try (InputStream is = isGzip() ? new GZIPInputStream(httpResponse.body()) : httpResponse.body()) {
                return new String(is.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private boolean isGzip() {
            return httpResponse.headers().firstValue("Content-Encoding").orElse("").equals("gzip");
        }
    }

    private HttpRequest.BodyPublisher asJson(Object arg) throws IOException {
        return HttpRequest.BodyPublishers.ofString(converter.toJson((MessageOrBuilder) arg));
    }
}
