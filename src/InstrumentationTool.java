import BIT.highBIT.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static java.nio.file.StandardOpenOption.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class InstrumentationTool {
	private static PrintStream out = null;
	private static ConcurrentHashMap<Long, Metrics> metricsPerThread = new ConcurrentHashMap<Long, Metrics>();

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
        
        
        if(infilename.endsWith(".class")) {
        	// Create class info object
        	ci = new ClassInfo(infilename);
        	
        	// Loop through all the routines
        	for(Enumeration methods = ci.getRoutines().elements(); methods.hasMoreElements(); ) {
        		routine = (Routine) methods.nextElement();
        		
        		if(routine.getMethodName().equals("<init>") || routine.getMethodName().equals("<clinit>"))
        			routine.addBefore("InstrumentationToo", "metricinit", "");
        		
        		if(routine.getMethodName().equals("readScene") || routine.getMethodName().equals("draw")) {
        			routine.addBefore("InstrumentationTool", "methodcount", new Integer(1));
        			
        			for(Enumeration blocks = routine.getBasicBlocks().elements(); blocks.hasMoreElements()) {
            			bb = (BasicBlock) blocks.nextElement();
            			bb.addBefore("InstrumentationTool", "bbcount", new Integer(bb.size()));
            		}
            		
            		for(Enumeration instrs = routine.getInstructionArray().elements(); instrs.hasMoreElements(); ) {
            			instr = (Instruction) instrs.nextElement();
            			int opcode = instr.getOpcode();
            			if(opcode == InstructionTable.putfield || opcode == InstructionTable.getfield)
            				instr.addBefore("InstrumentationTool", "fieldaccesscount", new Integer(1));
            			else {
            				short instr_type = InstructionTable.InstructionTypeTable[opcode];
            				if(instr_type == InstructionTable.LOAD_INSTRUCTION || instr_type == InstructionTable.STORE_INSTRUCTION)
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
	public static synchronized void metricStorage(String empty) {
		Charset utf8 = StandardCharsets.UTF_8;
		long threadId = Thread.currentThread().getId();
		List<String> loggerAux = new ArrayList<String>();
		Metrics metric;
		
		for(Map.Entry<Long,Metrics> entries : metricsPerThread.entrySet()) {
			threadId = entries.getKey();
			metric = entries.getValue();
			int method_count = metric.method_count;
			int bb_count = metric.bb_count;
			int instr_count = metric.instr_count;
			int fieldaccess_count = metric.fieldaccess_count;
			int memaccess_count = metric.memaccess_count;

			String aux = "Thread: " + String.valueOf(threadId) + " | Methods: " + String.valueOf(method_count) + 
					" | Blocks: " + String.valueOf(bb_count) + " | Instructions: " + String.valueOf(instr_count) + 
					" | FieldAccess: " + String.valueOf(fieldaccess_count) + " | MemAccess: " + String.valueOf(memaccess_count);
			loggerAux.add(aux);
		}
		try {
			Files.write(Paths.get("log.txt"), loggerAux, utf8, CREATE);
		} catch (IOException e) {
			System.out.println("Something went wrong with the logger!!!");
		}

    }
    

    
    
}
