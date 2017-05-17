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
import java.util.concurrent.ExecutorService;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
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
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;



public class LoadBalancer {
	private static AmazonEC2 ec2;
	private static ExecutorService executor;
	
	private static void init() {
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
      ec2 = AmazonEC2ClientBuilder.standard().withRegion("eu-west-2").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
	}
	
    public static void main(String[] args) throws Exception {
    	init();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        executor = Executors.newFixedThreadPool(10);
        server.createContext("/r.html", new MyHandler());
        server.setExecutor(executor); // creates a default executor
        server.start();
    }
    
    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String requestToSend = t.getRequestURI().getQuery();
            if(requestToSend!=null){
                DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
                List<Reservation> reservations = describeInstancesRequest.getReservations();
                Set<Instance> instances = new HashSet<Instance>();
                
                for (Reservation reservation : reservations) {
                	for (Instance testing : reservation.getInstances())
                		if(testing.getState().getName().equalsIgnoreCase(InstanceStateName.Running.name()))
                			instances.add(testing);
                }
                System.out.println("You have " + instances.size() + " Amazon EC2 instance(s) running.");
                System.out.println(requestToSend);
                for(Instance instan : instances) {
                	String response = "";
                	URL url = new URL("http://" + instan.getPublicIpAddress() + ":8000/r.html?" + requestToSend);
                	URLConnection conn = url.openConnection();
                	conn.setDoInput(true);
                	conn.setDoOutput(true);
                	conn.setReadTimeout(1000*60*10);
                	Scanner scan = new Scanner(conn.getInputStream());
                	
                	System.out.println("RECEIVED RESPONSE FROM WEBSERVER!");
                	while(scan.hasNext()) {
                		response += scan.next() + " ";
                	}
                	scan.close();

                	System.out.println("REDIRECTING BACK TO CLIENT!");
                	t.sendResponseHeaders(200, response.length());
           		 	OutputStream os = t.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                    System.out.println("REDIRECTION COMPLETED!");
                }

                
            }else{
                t.sendResponseHeaders(200, "LoadBalancer Health Check".length());
                OutputStream os= t.getResponseBody();
                os.write("health check".getBytes());
                os.close();
            }
        }
    }
}
