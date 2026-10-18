// A local stand-in for the map server, for testing what the plugin does when the server goes away and comes back.
// See README.md beside this file. Needs JDK 11 or newer; runs as a single file, nothing to build.
//
// Up: every request is forwarded to the live server. Down: the port is closed, so the plugin's requests are refused,
// as when the server is unreachable. Press Enter to toggle, or type up / down / quit.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.Executors;

public class MapServerProxy
{
    static final String DEFAULT_TARGET = "https://osrsmapreforged.online";
    static final int DEFAULT_PORT = 8099;
    /** Headers HttpClient will not take, or that describe the hop rather than the content. */
    static final Set<String> SKIPPED = Set.of("host", "connection", "content-length", "expect", "upgrade",
        "transfer-encoding", "keep-alive");

    static final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    static String target = DEFAULT_TARGET;
    static int port = DEFAULT_PORT;
    static HttpServer server;

    /** Arguments: [port] [target], e.g. 8099 https://osrsmapreforged.online */
    public static void main(String[] args) throws IOException
    {
        if (args.length > 0)
        {
            port = Integer.parseInt(args[0]);
        }
        if (args.length > 1)
        {
            target = args[1].replaceAll("/+$", "");
        }
        up();
        say("Point the client at it with -Dhdmap.server=http://127.0.0.1:" + port + " (developer mode)");
        var in = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = in.readLine()) != null; )
        {
            String command = line.trim().toLowerCase();
            if (command.isEmpty())
            {
                if (server == null)
                {
                    up();
                }
                else
                {
                    down();
                }
            }
            else if (command.equals("up"))
            {
                if (server == null)
                {
                    up();
                }
                else
                {
                    say("Already up");
                }
            }
            else if (command.equals("down"))
            {
                if (server != null)
                {
                    down();
                }
                else
                {
                    say("Already down");
                }
            }
            else if (command.equals("quit") || command.equals("exit"))
            {
                break;
            }
            else
            {
                say("Enter toggles; or up, down, quit");
            }
        }
        System.exit(0);
    }

    static void up() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.createContext("/", MapServerProxy::forward);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        say("UP: forwarding http://127.0.0.1:" + port + " to " + target + " (Enter to take it down)");
    }

    static void down()
    {
        server.stop(0);
        server = null;
        say("DOWN: port " + port + " closed, requests are refused (Enter to bring it back)");
    }

    static void forward(HttpExchange exchange) throws IOException
    {
        String path = exchange.getRequestURI().toString();
        try
        {
            var request = HttpRequest.newBuilder(URI.create(target + path))
                .timeout(Duration.ofSeconds(30))
                .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(
                    exchange.getRequestBody().readAllBytes()));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!SKIPPED.contains(name.toLowerCase()))
                {
                    values.forEach(value -> request.header(name, value));
                }
            });
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            response.headers().map().forEach((name, values) -> {
                if (!name.startsWith(":") && !SKIPPED.contains(name.toLowerCase()))
                {
                    exchange.getResponseHeaders().put(name, values);
                }
            });
            exchange.sendResponseHeaders(response.statusCode(), body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            // Tiles are many and dull; everything else, and any tile that failed, is worth seeing.
            if (!path.startsWith("/v1/tiles/") || path.endsWith("changed.json") || response.statusCode() != 200)
            {
                say(response.statusCode() + " " + path + " (" + body.length + " bytes)");
            }
        }
        catch (Exception e)
        {
            say("FAILED " + path + ": " + e);
            exchange.sendResponseHeaders(502, -1);
        }
        finally
        {
            exchange.close();
        }
    }

    static void say(String text)
    {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + "  " + text);
    }
}
