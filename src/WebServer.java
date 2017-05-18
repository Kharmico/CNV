import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import javax.xml.bind.DatatypeConverter;
import java.util.concurrent.ExecutorService;
import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.amazonaws.services.dynamodbv2.util.TableUtils.TableNeverTransitionedToStateException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import raytracer.RayTracer;


public class WebServer {
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
                String[] tokens = response.split("[&=]");
                String filename = tokens[1];
                String dir=System.getProperty("user.dir") + "\\info";
                String file=dir+File.separator+filename;
                System.out.println("file: "+file);
                String outputfile = String.valueOf(System.currentTimeMillis())+".bmp";
                
                int scols = Integer.parseInt(tokens[3]);
                int srows = Integer.parseInt(tokens[5]);
                int wcols = Integer.parseInt(tokens[7]);
                int wrows = Integer.parseInt(tokens[9]);
                int coff = Integer.parseInt(tokens[11]);
                int roff = Integer.parseInt(tokens[13]);
                
                File inputfilename = new File(file);
                File outputfilename = new File(outputfile);
                if(inputfilename.exists() && !inputfilename.isDirectory()) { 
                    System.out.println("There is "+inputfilename);
                }
                try {
                	InstrumentationTool.setValues(response);
	        		RayTracer rayTracer = new RayTracer(scols, srows, wcols, wrows, coff, roff);
	        		rayTracer.readScene(inputfilename);
	        		rayTracer.draw(outputfilename);
    			} catch (Exception e) {
                    e.printStackTrace();
    				System.out.println("Error while accessing RayTracer methods");
    			}
        		
        		Path pathToFile = Paths.get(outputfilename.getAbsolutePath());
        		byte[] fileContent = Files.readAllBytes(pathToFile);
        		
//        		t.getResponseHeaders().add("Content-Disposition", "attachment; filename=" + outputfile);    		
        		/* TODO: Gotta consult the input on the table so I put together the query params AND the metrics
        		 * on another table for ranking and such!
        		*/
        		
        		String stringToSend = DatatypeConverter.printBase64Binary(fileContent);
        		
                t.sendResponseHeaders(200, stringToSend.length());
                OutputStream os = t.getResponseBody();
                os.write(stringToSend.getBytes());
                os.close();
                long threadId = Thread.currentThread().getId();
                System.out.println("Thread finished execution: " + threadId);
            }else{
                t.sendResponseHeaders(200, "WebServer Health Check".length());
                OutputStream os= t.getResponseBody();
                os.write("health check".getBytes());
                os.close();
            }
        }
    }
}
