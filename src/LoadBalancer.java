import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import javax.xml.bind.DatatypeConverter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.Enumeration;
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
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;


public class LoadBalancer {
	private static final int TEN = 10;
	private static final String LIGHT = "LIGHT";
	private static final String MEDIUM = "MEDIUM";
	private static final String HEAVY = "HEAVY";
	private static final int MAX_HEAVY = 2;
	private static final int MAX_MEDIUM = 5;
	private static final int MAX_LIGHT = TEN;
	private static final String WS_PORT = "8000";
	private static final String R_HTML = "/r.html";
	private static final String TABLENAME = "RTMetrics";
	private static AWSCredentials credentials = null;
	private static AmazonDynamoDBClient dynamoDB;
	private static AmazonEC2 ec2;
	private static ExecutorService executor;
	private static List<Reservation> reservations;
	private static ConcurrentHashMap<Instance, Runners> runningInst;
	private static ConcurrentHashMap<String, String> rankedQuery; // String is the query params, Integer is the result of the metrics calculation
	private static ConcurrentHashMap<String, Instance> threadInstance;

	
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
		rankedQuery = new ConcurrentHashMap<String, String>();
		runningInst = new ConcurrentHashMap<Instance, Runners>();
		threadInstance = new ConcurrentHashMap<String, Instance>();
		try {
			credentials = new ProfileCredentialsProvider().getCredentials();
		} catch (Exception e) {
			throw new AmazonClientException("Cannot load the credentials from the credential profiles file. " +
					"Please make sure that your credentials file is at the correct " +
					"location (~/.aws/credentials), and is in valid format.", e);
		}
		ec2 = AmazonEC2ClientBuilder.standard().withRegion("eu-west-2").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
		dynamoDB= new AmazonDynamoDBClient(credentials);
        Region euWest2 = Region.getRegion(Regions.EU_WEST_2);
        dynamoDB.setRegion(euWest2);
	}
	
    public static void main(String[] args) throws Exception {
    	init();
    	ThreadHelper threadHelp = new ThreadHelper();
    	threadHelp.start();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        executor = Executors.newFixedThreadPool(10);
        server.createContext("/r.html", new MyHandler());
        server.setExecutor(executor); // creates a default executor
        server.start();
    }
    
    // Method to pick a WS where to send the request!
    private synchronized static String pickWS(String queryAux) {
    	String rank = "";
    	
    	// Get rank if it exists saved locally
    	if(rankedQuery.containsKey(queryAux))
    		rank = rankedQuery.get(queryAux);
    	else {
    		// Get rank if doesn't exist locally but on DynamoDB
	    	Map<String, AttributeValue> getitem = new HashMap<String, AttributeValue>();
	    	getitem.put("queryparam", new AttributeValue(queryAux));
	    	GetItemResult itemrec = dynamoDB.getItem(TABLENAME, getitem);
	    	if(itemrec.getItem() != null) {
	        	Map<String, AttributeValue> maprec = itemrec.getItem();
	        	AttributeValue rankrec = maprec.get("rank");
	        	String rankrecAux = rankrec.getS();
	        	rank = rankrecAux;
	        	rankedQuery.put(queryAux, rankrecAux);
	        }
    	}
    	
    	// Rank is unknown at the moment, going to estimate! How? Euclidian, calculations, checking all previous query params to
    	// see if one with really close values exist, if it does, then it's that one!
    	if(rank.equals("")){
        	String[] tokensQuery = queryAux.split("[&=]");
        	double valueToCheck = Integer.MAX_VALUE;
        	for(Map.Entry<String, String> queryEntries: rankedQuery.entrySet()){
        		String[] tokensEntry = queryEntries.getKey().split("[&=]");
                double eucliDist = Math.sqrt((Integer.parseInt(tokensQuery[3]) - Integer.parseInt(tokensEntry[3]))*2 + 
                		(Integer.parseInt(tokensQuery[5]) - Integer.parseInt(tokensEntry[5]))*2 + 
                		(Integer.parseInt(tokensQuery[7]) - Integer.parseInt(tokensEntry[7]))*2 + 
                		(Integer.parseInt(tokensQuery[9]) - Integer.parseInt(tokensEntry[9]))*2 + 
                		(Integer.parseInt(tokensQuery[11]) - Integer.parseInt(tokensEntry[11]))*2 + 
                		(Integer.parseInt(tokensQuery[13]) - Integer.parseInt(tokensEntry[13]))*2);
        		if(eucliDist < valueToCheck) {
        			valueToCheck = eucliDist;
        			rank = queryEntries.getValue();
        		}
        	}
        	if(rank.equals(""))
        		rank = HEAVY;
        }

    	// Pick the instance where to send the query to, by checking the load of queries on each one.

    	// 1 Heavy = 2 Medium; 1 Medium = 2 Light; 1 Heavy = 4 Light;
    	// Considering the max is 10 Lights (per say), then the ratios will have to go accordingly
    	// Beginning ratio is 10 because the max amount of requests going to a server is 10
    	int heavyAux = 0;
    	int mediumAux = 0;
    	int lightAux = 0;
    	int ratio = 11;
    	int ratioAux = 0;
    	Runners runAux = new Runners(0,0,0);
    	String addrAux = "";
    	Instance instanAux = new Instance();
    	for(Map.Entry<Instance, Runners> entries : runningInst.entrySet()){
    		if(runningInst.isEmpty())
    			break;
    		heavyAux = entries.getValue().heavy;
    		mediumAux = entries.getValue().medium;
    		lightAux = entries.getValue().light;
    		ratioAux = heavyAux*4+mediumAux*2+lightAux;
    		if(entries.getValue().equals(null) || entries.getValue().equals(runAux)){
    			if(rank.equals(LIGHT))
    				runAux.light++;
    			else if(rank.equals(MEDIUM))
    					runAux.medium++;
    			else runAux.heavy++;
    			runningInst.put(entries.getKey(), runAux);
    			return entries.getKey().getPublicIpAddress();
    		}
    		if(rank.equals(HEAVY) && ratioAux < TEN){
    			if(heavyAux == MAX_HEAVY)
    				continue;
    			else if(heavyAux == 1 && ratio > (ratioAux + 4)){
    				ratio = ratioAux + 4;
    				addrAux = entries.getKey().getPublicIpAddress();
    				instanAux = entries.getKey();
    			}
    			else if(ratio > (ratioAux + 4)){
    				ratio = ratioAux;
    				addrAux = entries.getKey().getPublicIpAddress();
    				instanAux = entries.getKey();
    			}
    		}
    		if(rank.equals(MEDIUM) && ratioAux < TEN){
    			if(ratio > (ratioAux + 2)){
    				ratio = ratioAux + 2;
    				addrAux = entries.getKey().getPublicIpAddress();
    				instanAux = entries.getKey();
    			}
    		}
    		if(rank.equals(LIGHT) && ratioAux < TEN){
    			if(ratio > (ratioAux + 1)){
    				ratio = ratioAux + 1;
    				addrAux = entries.getKey().getPublicIpAddress();
    				instanAux = entries.getKey();
    			}
    		}
    	}
    	
    	if(addrAux.equals(""))
    		return addrAux;
    	
    	if(rank.equals(LIGHT))
			runAux.light++;
		else if(rank.equals(MEDIUM))
				runAux.medium++;
		else runAux.heavy++;
    	runningInst.put(instanAux, runAux);
    	threadInstance.put(String.valueOf(Thread.currentThread().getId()), instanAux);
    	return addrAux;
    }
    
    // Method to process information after the Query was answered back to the client
    // Putting new information on the necessary structures.
    private synchronized static void postRequest(String queryAux) {
    	Map<String, AttributeValue> getitem = new HashMap<String, AttributeValue>();
    	getitem.put("queryparam", new AttributeValue(queryAux));
    	GetItemResult itemrec = dynamoDB.getItem(TABLENAME, getitem);
    	Map<String, AttributeValue> maprec = itemrec.getItem();
    	String rankrec = maprec.get(queryAux).toString();
    	rankedQuery.put(queryAux, rankrec);
    	System.out.print("The query params: " + queryAux + "\nThe rank of the query: " + rankrec);
    	
    	Instance instanAux = threadInstance.get(String.valueOf(Thread.currentThread().getId()));
    	Runners runAux = runningInst.get(instanAux);
    	String rankAux = rankedQuery.get(queryAux);
    	
    	if(rankAux.equals(HEAVY))
    		runAux.heavy--;
    	else if(rankAux.equals(LIGHT))
    		runAux.light--;
    	else runAux.medium--;
    	runningInst.put(instanAux, runAux);
    }
    
    
    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
        	String queryAux = t.getRequestURI().getQuery();
        	String chosenWS = "";
        	
	        chosenWS = pickWS(queryAux);

        	
        	URL url = new URL(String.format("http://%s:%s%s?%s", chosenWS, WS_PORT, R_HTML, queryAux));
        	HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        	connection.setDoInput(true);
        	connection.setDoOutput(true);
        	connection.setReadTimeout(1000 * 60 * TEN);
        	Scanner s = new Scanner(connection.getInputStream());
        	String response = "";
        	while(s.hasNext()){
        		response += s.next() + " ";
        	}
        	s.close();
        	byte[] receivedResponse = DatatypeConverter.parseBase64Binary(response);
        	t.sendResponseHeaders(200, receivedResponse.length);
            OutputStream os = t.getResponseBody();
            os.write(receivedResponse);
            os.flush();
            os.close();
        	
            postRequest(queryAux);
        }
    }
    
    
    private static InetAddress localhostAddress() {
		try {
			try {
				Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
				while (e.hasMoreElements()) {
					NetworkInterface n = e.nextElement();
					Enumeration<InetAddress> ee = n.getInetAddresses();
					while (ee.hasMoreElements()) {
						InetAddress i = ee.nextElement();
						if (i instanceof Inet4Address && !i.isLoopbackAddress())
							return i;
					}
				}
			} catch (SocketException e) {
				// do nothing
			}
			return InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			return null;
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
    		    				!runningInst.containsKey(instanceToCheck) && 
    		    				!instanceToCheck.getPublicIpAddress().equals(localhostAddress().getCanonicalHostName()))
    		    			runningInst.put(instanceToCheck, new Runners(0,0,0));
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