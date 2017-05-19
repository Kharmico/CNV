import BIT.highBIT.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static java.nio.file.StandardOpenOption.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.net.UnknownHostException;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.util.TableUtils;
import com.amazonaws.services.dynamodbv2.util.TableUtils.TableNeverTransitionedToStateException;


public class InstrumentationTool {
	private static final String TABLENAME = "RTMetrics";
	private static ConcurrentHashMap<Long, Metrics> metricsPerThread = new ConcurrentHashMap<Long, Metrics>();
	private static String queryParams;
	AmazonDynamoDBClient dynamoDB;

	// Class where to save the metrics counted and then save on DynamoDB
	static class Metrics {
		public int method_count;
		public int bb_count;
		public int instr_count;
		public int fieldaccess_count;
		public int memaccess_count;
		
		public Metrics(int m_count, int b_count, int i_count, int field_count, int mem_count){
			this.method_count = m_count;
			this.bb_count = bb_count;
			this.instr_count = instr_count;
			this.fieldaccess_count = fieldaccess_count;
			this.memaccess_count = memaccess_count;
		}
		public void reset() {
			this.method_count = 0;
			this.bb_count = 0;
			this.instr_count = 0;
			this.fieldaccess_count = 0;
			this.memaccess_count = 0;
		}
	}
	
	
	public static void main(String argv[]) {
        File file_in = new File(argv[0]);
        String infilename = file_in.toString();
        File file_out = new File(argv[1]);
        String outfilename = file_out.toString();
        ClassInfo ci;
        Routine routine;
        BasicBlock bb;
        Instruction instr;
        
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
        
     // Create a table with a primary hash key named 'name', which holds a string
        CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(TABLENAME)
            .withKeySchema(new KeySchemaElement().withAttributeName("queryparam").withKeyType(KeyType.HASH))
            .withAttributeDefinitions(new AttributeDefinition().withAttributeName("queryparam").withAttributeType(ScalarAttributeType.S))
            .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(10L));

        // Create table if it does not exist yet
        TableUtils.createTableIfNotExists(dynamoDB, createTableRequest);

        // wait for the table to move into ACTIVE state
        try {
			TableUtils.waitUntilActive(dynamoDB, TABLENAME);
		} catch (TableNeverTransitionedToStateException | InterruptedException e) {
			e.printStackTrace();
		}
        
        if(infilename.endsWith(".class")) {
        	// Create class info object
        	ci = new ClassInfo(infilename);
        	
        	// Loop through all the routines
        	for(Enumeration methods = ci.getRoutines().elements(); methods.hasMoreElements(); ) {
        		routine = (Routine) methods.nextElement();
        		
        		if(routine.getMethodName().equals("<init>") || routine.getMethodName().equals("<clinit>"))
        			routine.addBefore("InstrumentationTool", "metricinit", "");
        		
        		if(routine.getMethodName().equals("readScene") || routine.getMethodName().equals("draw")) {
        			routine.addBefore("InstrumentationTool", "methodcount", new Integer(1));
        			
        			for(Enumeration blocks = routine.getBasicBlocks().elements(); blocks.hasMoreElements(); ) {
            			bb = (BasicBlock) blocks.nextElement();
            			bb.addBefore("InstrumentationTool", "bbcount", new Integer(bb.size()));
            		}
            		
            		for(Enumeration instrs = routine.getInstructionArray().elements(); instrs.hasMoreElements(); ) {
            			instr = (Instruction) instrs.nextElement();
            			int opcode = instr.getOpcode();
            			if(opcode == InstructionTable.getfield)
            				instr.addBefore("InstrumentationTool", "fieldaccesscount", new Integer(1));
            			else {
            				short instr_type = InstructionTable.InstructionTypeTable[opcode];
            				if(instr_type == InstructionTable.LOAD_INSTRUCTION)
            					instr.addBefore("InstrumentationTool", "memaccesscount", new Integer(1));
            			}
            		}
        		}
        		
        		if(routine.getMethodName().equals("draw")) {
        			routine.addAfter("InstrumentationTool", "metricStorage", "");
        		}
        	}
        	ci.write(outfilename);
        }
    }
	
	// Extra function to receive the query parameters and save them as the key on the table
	public static void setValues(String queryParam) {
		queryParams = queryParam;
	}
	
	// Initialize the Metric, if not exists, for a given threadId
	public static synchronized void metricinit(String empty) {
		Metrics metric = new Metrics(1,0,0,0,0);
		long threadId = Thread.currentThread().getId();
		metricsPerThread.put(threadId, metric);
	}
	
    // Updates metrics for each threadId (method count)
    public static synchronized void methodcount(int incr) {
    	long threadId = Thread.currentThread().getId();
    	Metrics metric = metricsPerThread.get(threadId);
    	metric.method_count++;
    	metricsPerThread.put(threadId, metric);
    }
	
    // Updates metrics for each threadId (bblock count)
    public static synchronized void bbcount(int incr) {
    	long threadId = Thread.currentThread().getId();
    	Metrics metric = metricsPerThread.get(threadId);
    	metric.instr_count += incr;
    	metric.bb_count++;
    	metricsPerThread.put(threadId, metric);
    }
    
    // Updates metrics for each threadId (in case of getfield or putfield access)
	public static synchronized void fieldaccesscount(int incr) {
		long threadId = Thread.currentThread().getId();
		Metrics metric = metricsPerThread.get(threadId);
		metric.fieldaccess_count++;
		metricsPerThread.put(threadId, metric);
	}
	
	// Updates metrics for each threadId (in case of load or store)
	public static synchronized void memaccesscount(int incr) {
		long threadId = Thread.currentThread().getId();
		Metrics metric = metricsPerThread.get(threadId);
		metric.memaccess_count++;
		metricsPerThread.put(threadId, metric);
	}
	
	// Outputs the metrics to a log file!
	// logfile->  Thread: # | Methods: # | Blocks: # | Instructions: # | FieldAccess: # | MemAccess: #
	public static synchronized void metricStorage(String empty) throws UnknownHostException {
		long threadId = Thread.currentThread().getId();
		Metrics metric;
		
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
		
        metric = metricsPerThread.get(threadId);
		try {
			double rank = (metric.bb_count/100000)*0.1 + (metric.fieldaccess_count/100000)*0.1 + 
					(metric.instr_count/100000)*0.4 + (metric.memaccess_count/100000)*0.35 + metric.method_count*0.05;
			Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
	        item.put("queryparam", new AttributeValue(queryParams));
	        item.put("method", new AttributeValue(String.valueOf(metric.method_count)));
	        item.put("bb", new AttributeValue(String.valueOf(metric.bb_count)));
	        item.put("instr", new AttributeValue(String.valueOf(metric.instr_count)));
	        item.put("fieldaccess", new AttributeValue(String.valueOf(metric.fieldaccess_count)));
	        item.put("memaccess", new AttributeValue(String.valueOf(metric.memaccess_count)));
	        item.put("rankN", new AttributeValue(String.valueOf(rank)));
	        item.put("rankS", new AttributeValue("0"));
	        PutItemRequest putItemRequest = new PutItemRequest(TABLENAME, item).withConditionExpression("attribute_not_exists(queryparam)");
	        dynamoDB.putItem(putItemRequest);
	        metric.reset();
	        metricsPerThread.put(threadId, metric);
		} catch (AmazonServiceException ase) {
	        System.out.println("Caught an AmazonServiceException, which means your request made it "
	                + "to AWS, but was rejected with an error response for some reason.");
	        System.out.println("Error Message:    " + ase.getMessage());
	        System.out.println("HTTP Status Code: " + ase.getStatusCode());
	        System.out.println("AWS Error Code:   " + ase.getErrorCode());
	        System.out.println("Error Type:       " + ase.getErrorType());
	        System.out.println("Request ID:       " + ase.getRequestId());
	    } catch (AmazonClientException ace) {
	        System.out.println("Caught an AmazonClientException, which means the client encountered "
	                + "a serious internal problem while trying to communicate with AWS, "
	                + "such as not being able to access the network.");
	        System.out.println("Error Message: " + ace.getMessage());
	    }
    }
}