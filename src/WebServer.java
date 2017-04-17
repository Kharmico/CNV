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

import raytracer.RayTracer;

public class WebServer {

	private static ExecutorService executor;
	
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        executor = Executors.newFixedThreadPool(5);
        server.createContext("/r.html", new MyHandler());
        System.out.println("I CAME THROUGH HERE!!!");
        server.setExecutor(executor); // creates a default executor
        System.out.println("AND HERE TOO!!!");
        server.start();
    }

    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
        	System.out.println("NICE SHINY STUFF IN HERE!!!");
            String response = t.getRequestURI().getQuery();
            String outputfile = String.valueOf(System.currentTimeMillis());
            String[] tokens = response.split("[&=]");
            String filename = tokens[1];
            int scols = Integer.parseInt(tokens[3]);
            int srows = Integer.parseInt(tokens[5]);
            int wcols = Integer.parseInt(tokens[7]);
            int wrows = Integer.parseInt(tokens[9]);
            int coff = Integer.parseInt(tokens[11]);
            int roff = Integer.parseInt(tokens[13]);
            
            File inputfilename = new File(filename);
            File outputfilename = new File(outputfile + ".bmp");
    		RayTracer rayTracer = new RayTracer(scols, srows, wcols, wrows, coff, roff);
    		rayTracer.readScene(inputfilename);
    		try {
				rayTracer.draw(outputfilename);
			} catch (InterruptedException e) {
				System.out.println("Bad stuff happened here!!!");
			}
    		
    		Path pathToFile = Paths.get(outputfilename.getAbsolutePath());
    		byte[] fileContent = Files.readAllBytes(pathToFile);
    		
    		t.getResponseHeaders().add("Content-Disposition", "attachment; filename=" + outputfile);    		
    		
            t.sendResponseHeaders(200, fileContent.length);
            OutputStream os = t.getResponseBody();
            long threadId = Thread.currentThread().getId();
            System.out.println(threadId);
            os.write(fileContent);
            os.close();
        }
    }
}
