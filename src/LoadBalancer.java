import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import javax.xml.bind.DatatypeConverter;
import java.util.concurrent.ExecutorService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.elasticmapreduce.model.InstanceState;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;


public class LoadBalancer {
	private static AmazonEC2 ec2;
	private static ExecutorService executor;
	private static List<Reservation> reservations;
	private static Map<Instance, Runners> runningInst;
	private static Map<String, String> rankedQuery; // String is the query params, Integer is the result of the metrics calculation
	private static final String WS_PORT = "8000";
	private static final String R_HTML = "/r.html";
	private static final String TABLENAME = "RTMetrics";
	
	static class Runners {
		public int heavy;
		public int medium;
		public int light;
		
		public Runners(int heavy, int medium, int light) {
			this.heavy = heavy;
			this.medium = medium;
			this.light = light;
		}
	}
	
	// Method to initialize needed variables
	private static void init() {
		runningInst = new HashMap<Instance, Runners>();
		AWSCredentials credentials = null;
		try {
			credentials = new ProfileCredentialsProvider().getCredentials();
		} catch (Exception e) {
			throw new AmazonClientException("Cannot load the credentials from the credential profiles file. " +
					"Please make sure that your credentials file is at the correct " +
					"location (~/.aws/credentials), and is in valid format.", e);
		}
		ec2 = AmazonEC2ClientBuilder.standard().withRegion("eu-west-2").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
	}
	
    public static void main(String[] args) throws Exception {
    	init();
    	ThreadHelper threadHelp = new ThreadHelper();
    	threadHelp.start();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        executor = Executors.newCachedThreadPool();
        server.createContext("/r.html", new MyHandler());
        server.setExecutor(executor); // creates a default executor
        server.start();
    }
    
    // Method to pick a WS where to send the request!
    public static String pickWS(String queryAux) {
    	String rank;
    	
    	// Get rank if it exists saved locally
    	if(rankedQuery.containsKey(queryAux))
    		rank = rankedQuery.get(queryAux);
    	
    	// Get rank if doesn't exist locally but on DynamoDB
    	//TODO: Code to access DynamoDB and query!
    	
    	// Rank is unknown at the moment, going to estimate! How?
    	AWSCredentials credentials = null;
        try {
            credentials = new ProfileCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                    "Please make sure that your credentials file is at the correct " +
                    "location (~/.aws/credentials), and is in valid format.",
                    e);
        }
        AmazonDynamoDBClient dynamoDB = new AmazonDynamoDBClient(credentials);
        Region euWest2 = Region.getRegion(Regions.EU_WEST_2);
        dynamoDB.setRegion(euWest2);
    	
    	Runners runAux = new Runners(0,0,0);
    	for(Map.Entry<Instance, Runners> entries : runningInst.entrySet()){
    		if(entries.getValue().equals(null) || entries.getValue().equals(runAux))
    			return entries.getKey().getPublicIpAddress();
    	}
    	return "";
    }
    
    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
        	String queryAux = t.getRequestURI().getQuery();

        	String chosenWS = pickWS(queryAux);
        	
        	URL url = new URL(String.format("http://%s:%s%s?%s", chosenWS, WS_PORT, R_HTML, queryAux));
        	HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        	connection.setDoInput(true);
        	connection.setDoOutput(true);
        	connection.setReadTimeout(1000 * 60 * 10);
        	Scanner s = new Scanner(connection.getInputStream());
        	String response = "";
        	while(s.hasNext()){
        		response += s.next() + " ";
        	}
        	s.close();
        	byte[] receivedResponse = DatatypeConverter.parseBase64Binary(response);
        	t.sendResponseHeaders(200, receivedResponse.length);
        	t.getResponseBody().write(receivedResponse);
        	
        	
        }
    }
    
    public static class ThreadHelper extends Thread {
    	
    	@Override
    	public void run() {
    		while(true) {
	            DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
	            reservations = describeInstancesRequest.getReservations();
	            for (Reservation reservation : reservations) {
	            	for (Instance instanceToCheck : reservation.getInstances()) {
	            		if(instanceToCheck.getState().getName().equalsIgnoreCase(InstanceStateName.Running.name()) &&
	            				!runningInst.containsKey(instanceToCheck))
	            			runningInst.put(instanceToCheck, null);
	            	}
	            }
	            try {
					Thread.sleep(60000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
    		}
    	}
    }
}