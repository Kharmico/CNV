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
	private static ConcurrentHashMap<Long, Metrics> metricsPerThread = new ConcurrentHashMap();

	static class Metrics {
		public int i_count;
		public int b_count;
		public int m_count;
		
		public Metrics(int i_count, int b_count, int m_count){
			this.i_count = i_count;
			this.b_count = b_count;
			this.m_count = m_count;
		}
	}
	
	
	public static void main(String argv[]) {
		//metricsPerThread = new ConcurrentHashMap();
        File file_in = new File(argv[0]);
        String infilenames[] = file_in.list();
        
        for (int i = 0; i < infilenames.length; i++) {
            String infilename = infilenames[i];
            if (infilename.endsWith(".class")) {
				// create class info object
				ClassInfo ci = new ClassInfo(argv[0] + System.getProperty("file.separator") + infilename);
				
                // loop through all the routines
                // see java.util.Enumeration for more information on Enumeration class
                for (Enumeration e = ci.getRoutines().elements(); e.hasMoreElements(); ) {
                    Routine routine = (Routine) e.nextElement();
					routine.addBefore("InstrumentationTool", "mcount", new Integer(1));
                    
                    for (Enumeration b = routine.getBasicBlocks().elements(); b.hasMoreElements(); ) {
                        BasicBlock bb = (BasicBlock) b.nextElement();
                        bb.addBefore("InstrumentationTool", "count", new Integer(bb.size()));
                    }
                }
                ci.addAfter("InstrumentationTool", "printInstrumentationTool", ci.getClassName());
                ci.write(argv[1] + System.getProperty("file.separator") + infilename);
            }
        }
    }
	
	// Outputs the metrics to a log file!
	// logfile->  Thread: # | Instructions: # | Blocks: # | Methods: #
	public static synchronized void printInstrumentationTool(String foo) {
		Charset utf8 = StandardCharsets.UTF_8;
		List<String> loggerAux = new ArrayList<String>();
		
		for(Map.Entry<Long,Metrics> entries : metricsPerThread.entrySet()) {
			long threadId = entries.getKey();
			Metrics stuff = entries.getValue();
			int in_count = stuff.i_count;
			int bb_count = stuff.b_count;
			int me_count = stuff.m_count;
			String aux = "Thread: " + String.valueOf(threadId) + " | Instructions: " + String.valueOf(in_count) + 
					" | Blocks: " + String.valueOf(bb_count) + " | Methods: " + String.valueOf(me_count);
			loggerAux.add(aux);
		}
		try {
			Files.write(Paths.get("log.txt"), loggerAux, utf8, APPEND);
		} catch (IOException e) {
			System.out.println("Something went wrong with the logger!!!");
		}

    }
    
	// Updates metrics for each threadId (instruction and bblock count)
    public static synchronized void count(int incr) {
    	long threadId = Thread.currentThread().getId();
    	Metrics metric;
    	
    	if(!metricsPerThread.containsKey(threadId))
    		metric = new Metrics(incr,1,0);
    	else {
    		metric = metricsPerThread.get(threadId);
    		metric.i_count += incr;
    		metric.b_count++;
    	}
    	
    	metricsPerThread.put(threadId, metric);
    }

    // Updates metrics for each threadId (method count)
    public static synchronized void mcount(int incr) {
    	long threadId = Thread.currentThread().getId();
    	Metrics metric;
    	
    	if(!metricsPerThread.containsKey(threadId))
    		metric = new Metrics(0,0,1);
    	else {
    		metric = metricsPerThread.get(threadId);
    		metric.m_count++;
    	}
    	
    	metricsPerThread.put(threadId, metric);
    }
    
    
}
