import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;



public class LoadBalancer {

	private static ExecutorService executor;
	
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        executor = Executors.newFixedThreadPool(10);
        server.createContext("/r.html", new MyHandler());
        server.setExecutor(executor); // creates a default executor
        server.start();
    }
    
    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            
            String response = t.getRequestURI().getQuery();
            if(response!=null){
            	
            }else{
                t.sendResponseHeaders(200, "LoadBalancer Health Check".length());
                OutputStream os= t.getResponseBody();
                os.write("health check".getBytes());
                os.close();
            }
        }
    }

}
